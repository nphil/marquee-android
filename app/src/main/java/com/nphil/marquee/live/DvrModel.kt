package com.nphil.marquee.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.DvrAiring
import com.nphil.marquee.core.DvrItem
import com.nphil.marquee.core.DvrOption
import com.nphil.marquee.core.DvrRule
import com.nphil.marquee.core.DvrSchedule
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.core.dvrDelete
import com.nphil.marquee.core.dvrOptions
import com.nphil.marquee.core.dvrRecord
import com.nphil.marquee.core.dvrRecorded
import com.nphil.marquee.core.dvrSchedule
import com.nphil.marquee.core.dvrUnschedule
import com.nphil.marquee.core.dvrUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Separate from guide loading: a slow or restricted DVR never blocks the lineup or playback. */
@Stable
internal class DvrModel(private val container: AppContainer, private val guide: LiveGuideModel) {
	private val core get() = container.core
	private val server get() = guide.place.server
	private val remembered get() = guide.remembered
	private val scheduleLock = Mutex()
	val recordings = RecordingBrowser(container, guide)

	var schedule by mutableStateOf<DvrSchedule?>(null)
		private set
	var scheduleError by mutableStateOf<String?>(null)
		private set
	var recorded by mutableStateOf<List<DvrItem>?>(null)
		private set
	var recordedError by mutableStateOf<String?>(null)
		private set
	var recordedLoading by mutableStateOf(false)
		private set
	var working by mutableStateOf(false)
		private set
	private var byChannel by mutableStateOf<Map<String?, List<DvrAiring>>>(emptyMap())
	private var pending by mutableStateOf<Pending?>(null)

	val canRecord: Boolean get() = schedule != null

	fun airing(channel: String, start: Double): DvrAiring? =
		byChannel[channel]?.firstOrNull { abs(it.start - start) < 60 }

	fun requested(channel: String, programme: GuideProgramme): Boolean =
		pending?.let { it.channel == channel && it.id == programme.id } == true

	suspend fun refreshSchedule() = scheduleLock.withLock {
		try {
			val next = core.dvrSchedule(server, remembered)
			byChannel = next.scheduled.groupBy { it.channelKey }
			schedule = next
			scheduleError = null
			pending?.let {
				if (airing(it.channel, it.start) != null || wallTime() >= it.until) pending = null
			}
		} catch (error: CoreException) {
			if (error.refusedDvr) {
				schedule = null
				byChannel = emptyMap()
				pending = null
			}
			scheduleError = problem(error, "load the recording schedule")
		}
	}

	suspend fun loadRecorded(refresh: Boolean = false) {
		if (recordedLoading || (!refresh && recorded != null)) return
		recordedLoading = true
		try {
			recorded = core.dvrRecorded(server, remembered)
			recordedError = null
		} catch (error: CoreException) {
			if (error.refusedDvr) recorded = null
			recordedError = problem(error, "load recordings")
		} finally {
			recordedLoading = false
		}
	}

	suspend fun options(programme: GuideProgramme): List<DvrOption> = core.dvrOptions(server, remembered, programme.guid)

	fun record(channel: String, programme: GuideProgramme, option: DvrOption, values: Map<String, String>, finished: (String?) -> Unit) =
		mutate("schedule the recording", finished, afterRefresh = {
			if (requested(channel, programme)) {
				delay(2_000)
				refreshSchedule()
			}
		}) {
			val ready = container.live.recordAfterTunerRelease {
				core.dvrRecord(server, remembered, programme.guid, option.type, values)
			}
			if (!ready) throw TunerReleasePending()
			pending = Pending(channel, programme.id, programme.start, wallTime() + 60)
		}

	fun update(rule: DvrRule, values: Map<String, String>, finished: (String?) -> Unit) =
		mutate("update the rule", finished) { core.dvrUpdate(server, remembered, rule.id, values) }

	fun unschedule(airing: DvrAiring, deletingSeries: Boolean, finished: (String?) -> Unit) =
		mutate(if (airing.isRecording) "stop the recording" else "cancel the airing", finished) {
			core.dvrUnschedule(server, remembered, airing, deletingSeries)
		}

	fun delete(rule: DvrRule, finished: (String?) -> Unit) =
		mutate("delete the rule", finished) { core.dvrDelete(server, remembered, rule.id) }

	private fun mutate(action: String, finished: (String?) -> Unit, afterRefresh: suspend () -> Unit = {}, write: suspend () -> Unit) {
		if (working) return
		working = true
		container.scope.launch {
			try {
				write()
				finished(null)
				refreshSchedule()
				afterRefresh()
			} catch (_: TunerReleasePending) {
				finished("The tuner hasn't confirmed its release yet. Try again.")
			} catch (error: CoreException) {
				finished(problem(error, action))
				if (error.status == 409 || error.refusedDvr) refreshSchedule()
			} finally {
				working = false
			}
		}
	}

	fun problem(error: CoreException, action: String): String = when {
		error.refusedDvr -> "Plex didn't let this profile $action."
		error.status == 409 -> "The recording schedule changed. Check it and try again."
		error.failure == "offline" -> "This device is offline."
		error.failure == "timedOut" -> "${server.name} took too long to answer."
		error.kind == CoreException.Kind.Unreachable || error.kind == CoreException.Kind.NoConnections -> "${server.name} couldn't be reached."
		else -> "Plex couldn't $action."
	}

	private class TunerReleasePending : Exception()
	private data class Pending(val channel: String, val id: String, val start: Double, val until: Double)
}

private val CoreException.refusedDvr: Boolean
	get() = kind == CoreException.Kind.Unauthorized || status == 401 || status == 403

package com.nphil.marquee.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nphil.marquee.AppContainer
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideGenre
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.core.liveGenre
import com.nphil.marquee.core.liveGuide
import com.nphil.marquee.data.LiveLists
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One active server/profile guide, shared between the shell and its player. Loading is owned by the visible page. */
internal class LiveTv(private val container: AppContainer, cache: File) {
	val guide = container.browse.source.place.map { it?.let { LiveGuideModel(container, it, cache) } }
		.stateIn(container.scope, SharingStarted.Eagerly, null)

	/** One-tuner servers cannot accept the next tune until the preceding grab has gone. */
	val operations = Mutex()

	val dvrRelease = DvrTunerRelease()

	/** MIME types a tuner's stream held that this tablet failed to decode this run; direct is not tried for them again. */
	val undecodable: MutableSet<String> = ConcurrentHashMap.newKeySet()

	suspend fun recordAfterTunerRelease(write: suspend () -> Unit): Boolean =
		operations.withLock { dvrRelease.beforeRecord(write) }
}

internal class DvrTunerRelease {
	private var releasing: TunerCandidate? = null

	fun stopped(tuner: TunerCandidate) {
		if (tuner.isLuma) releasing = tuner
	}

	/** Closing the HTTP socket precedes the TV seeing it close; Plex must not book a grab in that gap. */
	suspend fun beforeRecord(write: suspend () -> Unit): Boolean {
		while (true) {
			val tuner = releasing ?: break
			when (tuner.afterRelease()) {
				TunerAvailability.Free, is TunerAvailability.Held -> if (releasing === tuner) releasing = null
				else -> return false
			}
		}
		write()
		return true
	}
}

@Stable
internal class LiveGuideModel(private val container: AppContainer, val place: BrowsePlace, cache: File) {
	val key = storageKey(place)
	val directory = cache.resolve(key).path
	val lists = container.settings.liveLists(key)
	val remembered: String? get() = container.session.state.value.addresses[place.server.id]
	val dvr = DvrModel(container, this)

	var now by mutableDoubleStateOf(wallTime())
		private set
	var guide by mutableStateOf<GuideWindow?>(null)
		private set
	var noDvr by mutableStateOf(false)
		private set
	var refreshedAt by mutableStateOf<Double?>(null)
		private set
	var problem by mutableStateOf<String?>(null)
		private set
	var refreshing by mutableStateOf(false)
		private set
	private var lastAttempt: Double? = null
	private var readCache = false
	private val loading = Mutex()
	private val genres = mutableMapOf<GuideGenre, Set<String>>()

	suspend fun visible() {
		while (true) {
			now = wallTime()
			val attempted = lastAttempt
			if (attempted == null || now !in attempted..(attempted + STALE_SECONDS)) refresh()
			delay(30_000)
		}
	}

	suspend fun refresh(wait: Boolean = false) {
		if (!loading.tryLock()) {
			if (!wait) return
			loading.lock()
			if (guide != null) {
				loading.unlock()
				return
			}
		}
		refreshing = true
		val at = wallTime()
		now = at
		val server = place.server
		try {
			if (!readCache) {
				if (guide == null) guide = container.core.liveGuide(server, remembered, directory, at, cached = true)
				readCache = true
			}
			val first = container.core.liveGuide(server, remembered, directory, at)
			// Don't replace an already loaded day with the short window while a refresh is in flight.
			if (first?.run == null || guide == null) guide = first
			noDvr = first == null
			problem = null
			refreshedAt = at
			genres.clear()
			first?.run?.let { run ->
				val full = container.core.liveGuide(server, remembered, directory, at, run = run)
				guide = full ?: first
			}
			lastAttempt = at
		} catch (error: CoreException) {
			problem = liveProblem(error, server.name, tuning = false)
			noDvr = false
			lastAttempt = at
		} finally {
			refreshing = false
			loading.unlock()
		}
	}

	suspend fun genre(filter: GuideGenre): Set<String> = genres[filter] ?: container.core
		.liveGenre(place.server, remembered, filter).also { genres[filter] = it }

	fun toggleFavourite(channel: GuideChannel) {
		container.scope.launch {
			container.settings.updateLiveLists(key) { old ->
				val favourites = old.favourites
				old.copy(favourites = if (channel.id in favourites) favourites - channel.id else favourites + channel.id)
			}
		}
	}

	fun watched(channel: GuideChannel) {
		container.scope.launch {
			container.settings.updateLiveLists(key) { old -> old.copy(recents = recentChannels(old.recents, channel.id)) }
		}
	}

	private companion object {
		const val STALE_SECONDS = 600.0
	}
}

internal fun liveChannels(guide: GuideWindow, category: String, lists: LiveLists, keys: Set<String>?): List<GuideChannel> =
	when (category) {
		"all" -> guide.channels
		"favourites" -> guide.channels.filter { it.id in lists.favourites }
		"recent" -> {
			val channels = guide.channels.associateBy { it.id }
			lists.recents.mapNotNull(channels::get)
		}
		else -> guide.channels.filter { keys?.contains(it.id) == true }
	}

internal fun recentChannels(previous: List<String>, watched: String): List<String> =
	(listOf(watched) + (previous - watched)).take(10)

internal fun wallTime(): Double = System.currentTimeMillis() / 1000.0

private fun storageKey(place: BrowsePlace): String {
	// When Plex has not supplied a user ID, don't give a different account on the same server the owner's old guide.
	val profile = place.profile.ifBlank { place.server.accessToken.orEmpty() }
	val bytes = MessageDigest.getInstance("SHA-256").digest("${place.server.id}\u0000$profile".encodeToByteArray())
	return bytes.joinToString("") { "%02x".format(it) }
}

internal fun liveProblem(error: CoreException, server: String, tuning: Boolean): String = when {
	error.kind == CoreException.Kind.Unauthorized || error.status == 401 || error.status == 403 ->
		"Plex didn't let this profile ${if (tuning) "watch Live TV" else "open the guide"}."
	error.status == 503 && tuning -> "The tuner is busy or isn't responding."
	error.failure == "offline" -> "This device is offline."
	error.failure == "timedOut" -> "$server took too long to answer."
	error.kind == CoreException.Kind.Unreachable || error.kind == CoreException.Kind.NoConnections -> "$server couldn't be reached."
	else -> if (tuning) "Plex couldn't tune this channel." else "Plex couldn't load the schedule."
}

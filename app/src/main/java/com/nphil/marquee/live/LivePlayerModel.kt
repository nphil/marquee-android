package com.nphil.marquee.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.LiveStream
import com.nphil.marquee.core.closeLive
import com.nphil.marquee.core.openLive
import com.nphil.marquee.core.reportLive
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.PlaybackFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
@Stable
internal class LivePlayerModel(
	private val container: AppContainer,
	context: Context,
	http: OkHttpClient,
	val guide: LiveGuideModel,
	private val firstChannel: String,
) : ViewModel() {
	private val app = context.applicationContext
	private val factory = PlaybackFactory(app, http)
	private val tuner = DirectTuner(app, http)
	private val window = Timeline.Window()
	private var generation = 0
	private var loaded = -1
	private var closed = false
	private var stream: LiveStream? = null
	private var direct: DirectTunerFeed? = null
	private var outgoingTuner: TunerCandidate? = null
	private var resolving: Job? = null
	private var recordingGuard: Job? = null
	private var statusTimer: Job? = null
	private var heartbeat: Job? = null
	private var timeout: Job? = null
	private var revealed: GuideChannel? = null
	private var wantedPlaying = true
	private var published = false
	private var backgroundAllowed = false
	private val open = container.playback.holdPlayerOpen()
	private var audio: com.nphil.marquee.playback.PlaybackCoordinator.Hold? = null

	var player: ExoPlayer? by mutableStateOf(null)
		private set
	var channel: GuideChannel? by mutableStateOf(null)
		private set
	var lastChannel: GuideChannel? by mutableStateOf(null)
		private set
	var playing by mutableStateOf(false)
		private set
	var paused by mutableStateOf(false)
		private set
	var phase by mutableStateOf<LivePlayerPhase>(LivePlayerPhase.Tuning)
		private set
	var position by mutableStateOf(LivePosition())
		private set
	var status: String? by mutableStateOf(null)
		private set

	init {
		viewModelScope.launch {
			container.browse.source.place.first {
				it == null || it.server.id != guide.place.server.id || it.profile != guide.place.profile
			}
			close()
		}
	}

	/** A restored player has a channel key, never a server credential or a stale playlist. */
	suspend fun start(refresh: Boolean = false) {
		if (channel != null || closed) return
		if (guide.guide == null || refresh) guide.refresh(wait = true)
		if (closed) return
		val initial = guide.guide?.channels?.firstOrNull { it.id == firstChannel }
		if (initial == null) phase = LivePlayerPhase.Failed(guide.problem ?: "This channel is no longer in the lineup.")
		else tune(initial)
	}

	fun tune(next: GuideChannel, retry: Boolean = false) {
		if (closed || (!retry && next.id == channel?.id && phase !is LivePlayerPhase.Failed && phase != LivePlayerPhase.Away)) return
		queueTune(next)
	}

	private fun queueTune(next: GuideChannel, plexNotice: String? = null, plexReason: String? = null) {
		val mine = ++generation
		stopMedia()
		announce(plexNotice)
		val previous = revealed
		if (previous != null && previous.id != next.id) lastChannel = previous
		channel = next
		phase = LivePlayerPhase.Tuning
		container.scope.launch {
			container.live.operations.withLock {
				if (mine != generation) return@withLock
				finishStream()
				if (mine != generation) return@withLock
				val choice = coroutineScope {
					async { chooseRoute(next, plexReason) }
						.also { resolving = it }.await()
				}
				resolving = null
				if (mine != generation) return@withLock
				outgoingTuner = null
				when (choice) {
					is TuneChoice.Direct -> {
						direct = choice.feed
						Diag.info(DiagCategory.Player, "live.route", "route" to "direct", "reason" to "eligible")
						play(next, mine)
						guardRecordings(next, choice.feed.tuner, choice.handover, mine)
						return@withLock
					}
					is TuneChoice.Refused -> {
						Diag.info(DiagCategory.Player, "live.route", "route" to "stopped", "reason" to choice.reason)
						fail(choice.message)
						return@withLock
					}
					is TuneChoice.Plex -> Diag.info(DiagCategory.Player, "live.route", "route" to "plex", "reason" to choice.reason)
				}
				try {
					// Do not cancel the POST if the screen closes. Once it returns, its grab can be released.
					val opened = container.core.openLive(guide.place.server, guide.remembered, next)
					stream = opened
					if (mine != generation) {
						finishStream()
						return@withLock
					}
					play(next, mine)
				} catch (error: CoreException) {
					if (mine == generation) fail(liveProblem(error, guide.place.server.name, tuning = true))
				}
			}
		}
	}

	private suspend fun chooseRoute(next: GuideChannel, plexReason: String?): TuneChoice {
		if (!container.settings.playback(guide.place.profile).first().readsTunerDirectly) return TuneChoice.Plex("setting")
		if (!canReadLocalTuner(app)) return TuneChoice.Plex("local_network_permission")
		val reason = plexReason ?: run {
			val candidate = tuner.probe(next)
			val plan = candidate?.let { readPlan(next, it, timeoutMs = 4_000) }
			when {
				candidate == null -> "tuner_not_reachable"
				plan == null -> "schedule_not_verified"
				plan.path == DirectPlan.Path.Protected -> return TuneChoice.Refused("This channel is protected.", plan.reason)
				plan.path == DirectPlan.Path.Plex -> plan.reason
				else -> {
					val available = candidate.availability()
					if (available is TunerAvailability.Held) return TuneChoice.Refused(available.message, "tuner_in_use")
					if (!candidate.isLuma || available == TunerAvailability.Free || available == TunerAvailability.Ours) {
						plan.url?.let(candidate::open)?.let { return TuneChoice.Direct(it, plan.handover) }
						"invalid_stream_origin"
					} else "claim_not_verified"
				}
			}
		}
		when (val lease = outgoingTuner?.afterRelease()) {
			TunerAvailability.Releasing ->
				return TuneChoice.Refused("The tuner is still releasing the previous channel.", "tuner_releasing")
			is TunerAvailability.Held -> if (plexReason == "direct_failed") return TuneChoice.Refused(lease.message, "tuner_in_use")
			else -> Unit
		}
		return TuneChoice.Plex(reason)
	}

	private suspend fun readPlan(next: GuideChannel, candidate: TunerCandidate, timeoutMs: Long): DirectPlan? =
		withTimeoutOrNull(timeoutMs) {
			try {
				container.core.planDirect(guide.place.server, guide.remembered, next, candidate)
			} catch (error: CoreException) {
				Diag.warning(DiagCategory.Player, "live.recordings.unavailable", "kind" to error.kind.name)
				null
			}
		}

	private fun guardRecordings(next: GuideChannel, candidate: TunerCandidate, initial: RecordingHandover?, mine: Int) {
		recordingGuard = container.scope.launch {
			var handover = initial
			var warned: Double? = null
			while (mine == generation) {
				val left = handover?.remainingMs() ?: Long.MAX_VALUE
				if (handover != null && left <= 0) {
					val number = handover.number ?: next.number
					if (handover.sameChannel) queueTune(next, "Recording $number now · playing through Plex", "recording_handover")
					else {
						Diag.info(DiagCategory.Player, "live.route", "route" to "stopped", "reason" to "recording_handover")
						fail("Live TV stopped for the recording on $number.")
					}
					return@launch
				}
				if (handover != null && left <= 60_000 && warned != handover.releaseAt) {
					warned = handover.releaseAt
					announce(handover.number?.let { "Recording on $it starts soon." } ?: "A recording starts soon.")
				}
				delay(minOf(60_000L, if (left in 60_001L..120_000L) left - 60_000L else left))
				val remaining = handover?.remainingMs() ?: Long.MAX_VALUE
				if (mine != generation) return@launch
				if (remaining <= 0) continue
				val updated = readPlan(next, candidate, timeoutMs = minOf(4_000, remaining))
				if (mine != generation) return@launch
				if ((handover?.remainingMs() ?: Long.MAX_VALUE) <= 0) continue
				if (updated == null || (updated.path != DirectPlan.Path.Direct && updated.handover == null)) {
					Diag.info(DiagCategory.Player, "live.route", "route" to "stopped", "reason" to "schedule_not_verified")
					fail("Live TV stopped because upcoming recordings couldn't be checked.")
					return@launch
				}
				handover = updated.handover
			}
		}
	}

	private fun recoverDirect(problem: DirectProblem = DirectProblem.Fallback) {
		val next = channel ?: return
		when (val recovery = direct?.recover(problem)) {
			DirectProblem.Fallback -> queueTune(next, "Couldn't play from the tuner · playing through Plex", "direct_failed")
			is DirectProblem.Refused -> {
				Diag.info(DiagCategory.Player, "live.route", "route" to "stopped", "reason" to recovery.reason)
				fail(recovery.message)
			}
			null -> Unit
		}
	}

	private fun announce(message: String?) {
		statusTimer?.cancel()
		status = message
		if (message != null) statusTimer = container.scope.launch {
			delay(8_000)
			status = null
		}
	}

	fun step(by: Int) {
		val lineup = guide.guide?.channels.orEmpty()
		val current = lineup.indexOfFirst { it.id == channel?.id }
		if (current < 0 || lineup.size < 2) return
		tune(lineup[(current + by).mod(lineup.size)])
	}

	fun retry() {
		channel?.let { tune(it, retry = true) }
			?: container.scope.launch { start(refresh = true) }
	}

	fun toggle() {
		if (closed || phase is LivePlayerPhase.Failed || phase == LivePlayerPhase.Away) return
		wantedPlaying = !wantedPlaying
		paused = !wantedPlaying
		if (loaded == generation) player?.playWhenReady = wantedPlaying
	}

	fun skip(seconds: Int) = seekBehind(liveSkip(position, seconds))

	fun seekBehind(ms: Long) {
		val engine = player ?: return
		if (loaded != generation || !engine.isCurrentMediaItemSeekable || position.reach == 0L) return
		val target = ms.coerceIn(0L, position.reach)
		if (target < 3000L) goLive()
		else {
			engine.seekTo((position.reach - target).coerceAtLeast(0))
			resume()
		}
	}

	fun goLive() {
		val engine = player ?: return
		if (loaded != generation) return
		if (engine.isCurrentMediaItemSeekable) engine.seekToDefaultPosition()
		resume()
	}

	private fun resume() {
		wantedPlaying = true
		paused = false
		player?.play()
	}

	/** Only Plex carries on as audio in the background; a direct tune holds the TV's tuner. */
	fun background(allowed: Boolean) {
		backgroundAllowed = allowed
		publishIfWanted()
	}

	fun leftScreen(fromPip: Boolean) {
		if (closed) return
		if (direct != null || fromPip || !backgroundAllowed || !playing) {
			if (phase != LivePlayerPhase.Away && phase !is LivePlayerPhase.Failed) {
				endPlayback()
				phase = LivePlayerPhase.Away
			}
		}
	}

	fun close() {
		if (closed) return
		closed = true
		endPlayback()
		open.release()
	}

	override fun onCleared() = close()

	private val listener = object : Player.Listener {
		override fun onEvents(engine: Player, events: Player.Events) {
			if (closed || loaded != generation || phase is LivePlayerPhase.Failed) return
			playing = engine.isPlaying
			wantedPlaying = engine.playWhenReady
			paused = !wantedPlaying
			val buffering = engine.playbackState == Player.STATE_BUFFERING
			phase = when {
				buffering && revealed == channel -> LivePlayerPhase.Buffering
				buffering -> LivePlayerPhase.Tuning
				engine.playbackState == Player.STATE_READY -> LivePlayerPhase.Ready
				else -> phase
			}
			if (playing) {
				if (audio == null) audio = container.playback.holdAudio()
				if (channel != revealed) channel?.let { revealed = it; guide.watched(it) }
			} else releaseAudio()
			readPosition(engine)
		}

		override fun onPlayerError(error: PlaybackException) {
			if (loaded != generation) return
			if (direct != null) {
				Diag.warning(DiagCategory.Player, "live.direct.failed", "code" to error.errorCodeName)
				recoverDirect(directProblem(error.errorCode, error.cause))
				return
			}
			if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
				// A long pause can outlive the server's rolling window. There is nothing left at that position.
				player?.let { it.seekToDefaultPosition(); it.prepare() }
			} else {
				Diag.warning(DiagCategory.Player, "live.play.failed", "code" to error.errorCodeName)
				fail("The live stream stopped unexpectedly.")
			}
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (loaded != generation || direct == null || tracks.isEmpty) return
			if ((tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) ||
				(tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO))) recoverDirect()
		}

		override fun onRenderedFirstFrame() {
			if (loaded == generation && direct != null) timeout?.cancel()
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			if (loaded != generation) return
			val feed = direct
			if (playbackState == Player.STATE_READY && feed == null) timeout?.cancel()
			if (playbackState == Player.STATE_ENDED) {
				if (feed == null) fail("Plex ended this live stream.")
				else recoverDirect(feed.endProblem())
			}
		}
	}

	private fun play(channel: GuideChannel, mine: Int) {
		val opened = stream
		val tuner = direct
		val headers = opened?.headers.orEmpty()
		val engine = player ?: factory.create(headers, PlaybackFactory.Kind.Viewer).also {
			it.addListener(listener)
			it.setWakeMode(C.WAKE_MODE_NETWORK)
			player = it
		}
		val about = MediaMetadata.Builder().setTitle(channel.label).setArtist("Live TV").build()
		engine.setMediaSource(tuner?.source(about) ?: factory.source(headers, checkNotNull(opened).url, hls = true, about = about))
		engine.playWhenReady = wantedPlaying
		loaded = mine
		// The socket times out after five idle seconds. Give Media3 time to buffer and decode its first picture.
		timeout = container.scope.launch {
			delay(if (tuner == null) 20_000 else 12_000)
			if (loaded == generation) {
				if (tuner != null) recoverDirect()
				else if (phase == LivePlayerPhase.Tuning) fail("Plex took too long to start the channel.")
			}
		}
		engine.prepare()
		publishIfWanted()
		if (opened == null) return
		heartbeat = container.scope.launch {
			var lastState: String? = null
			var ticks = 0
			while (loaded == generation) {
				readPosition(engine)
				val state = when {
					paused -> "paused"
					playing -> "playing"
					else -> "buffering"
				}
				if (state != lastState || ticks == 10) {
					try {
						container.core.reportLive(opened.session, state)
					} catch (error: CoreException) {
						Diag.warning(DiagCategory.Player, "live.report.failed", "kind" to error.kind.name)
					}
					lastState = state
					ticks = 0
				}
				delay(1_000)
				ticks++
			}
		}
	}

	private fun readPosition(engine: Player) {
		val timeline = engine.currentTimeline
		if (timeline.isEmpty || !engine.isCurrentMediaItemSeekable) {
			position = LivePosition()
			return
		}
		timeline.getWindow(engine.currentMediaItemIndex, window)
		// Use Media3's recommended live start, not the end of the playlist (which has a safety margin).
		val current = engine.currentPosition
		val default = window.defaultPositionMs.takeIf { it != C.TIME_UNSET } ?: 0L
		val target = engine.currentLiveOffset.takeIf { it != C.TIME_UNSET }?.let { offset ->
			val configured = window.liveConfiguration?.targetOffsetMs?.takeIf { it != C.TIME_UNSET }
			configured?.let { (offset - it + current).coerceAtLeast(0) }
		} ?: default
		position = LivePosition((target - current).coerceAtLeast(0), target)
	}

	private fun publishIfWanted() {
		val engine = player
		val allowed = backgroundAllowed && direct == null
		if (allowed && !published && engine != null) {
			container.playback.publish(app, engine)
			published = true
		} else if (!allowed && published) {
			container.playback.retract(app)
			published = false
		}
	}

	private fun fail(message: String) {
		endPlayback()
		phase = LivePlayerPhase.Failed(message)
	}

	private fun endPlayback() {
		val retired = ++generation
		stopMedia()
		announce(null)
		if (published) {
			container.playback.retract(app)
			published = false
		}
		player?.let { it.removeListener(listener); it.release() }
		player = null
		container.scope.launch {
			container.live.operations.withLock {
				if (retired == generation || loaded != generation) finishStream()
			}
		}
	}

	private fun stopMedia() {
		resolving?.cancel()
		resolving = null
		recordingGuard?.cancel()
		heartbeat?.cancel()
		timeout?.cancel()
		loaded = -1
		direct?.let { feed ->
			outgoingTuner = feed.tuner
			feed.stop()
			container.live.dvrRelease.stopped(feed.tuner)
		}
		direct = null
		player?.stop()
		releaseAudio()
		playing = false
		position = LivePosition()
	}

	private fun releaseAudio() {
		audio?.release()
		audio = null
	}

	private suspend fun finishStream() {
		heartbeat?.cancelAndJoin()
		heartbeat = null
		val old = stream ?: return
		stream = null
		try {
			container.core.closeLive(old.session)
		} catch (error: CoreException) {
			Diag.warning(DiagCategory.Player, "live.close.failed", "kind" to error.kind.name)
		}
	}
}

private sealed interface TuneChoice {
	data class Direct(val feed: DirectTunerFeed, val handover: RecordingHandover?) : TuneChoice
	data class Refused(val message: String, val reason: String) : TuneChoice
	data class Plex(val reason: String) : TuneChoice
}

private fun RecordingHandover.remainingMs(): Long = (releaseAt * 1_000).toLong() - System.currentTimeMillis()

internal sealed interface LivePlayerPhase {
	data object Tuning : LivePlayerPhase
	data object Buffering : LivePlayerPhase
	data object Ready : LivePlayerPhase
	data object Away : LivePlayerPhase
	data class Failed(val message: String) : LivePlayerPhase
}

internal data class LivePosition(val behind: Long = 0L, val reach: Long = 0L) {
	val canGoBack: Boolean get() = reach - behind >= 1000L
}

internal fun liveSkip(position: LivePosition, seconds: Int): Long {
	return (position.behind - seconds * 1000L).coerceIn(0L, position.reach)
}

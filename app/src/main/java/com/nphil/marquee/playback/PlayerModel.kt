package com.nphil.marquee.playback

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.Frame
import com.nphil.marquee.core.LinkProbe
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.Reading
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.StartRequest
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.core.playRemember
import com.nphil.marquee.core.playReport
import com.nphil.marquee.core.playStop
import com.nphil.marquee.core.setWatched
import com.nphil.marquee.core.startPlay
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where opening a title has got to. */
sealed interface PlayerPhase {
	/** Choosing the stream: asking the server, reading the link. */
	data object Starting : PlayerPhase

	/** A player is open on the stream; it may still be buffering or paused. */
	data object Playing : PlayerPhase

	data class Failed(val message: String) : PlayerPhase
}

/**
 * One title playing in the full-screen player: Core decides the route ([startPlay]), Media3 performs it, and the
 * server hears where playback is. It lives as long as the player's page is on the back stack, so a turn of the
 * screen or a resized window does not restart the title. Everything runs on the main thread.
 */
@OptIn(UnstableApi::class)
@Stable
class PlayerModel(
	private val container: AppContainer,
	/** What fetches the video and the link reading: the app's client, or a test's that trusts the fake server. */
	private val http: OkHttpClient,
	context: Context,
	private val ratingKey: String,
	/** What the profile's remembered tracks are filed under: the show for an episode, else the title itself. */
	private val titleKey: String,
	private val fromStart: Boolean,
) : ViewModel() {
	private val app = context.applicationContext
	private val factory = PlaybackFactory(app, http)

	/** The audio and subtitle tracks the menus list, and the subtitle on screen. */
	internal val tracks = PlayerTracks(viewModelScope, http, ::movieMs)

	var phase: PlayerPhase by mutableStateOf(PlayerPhase.Starting)
		private set

	/** The open player; null while the stream is chosen and after a failure. */
	var player: ExoPlayer? by mutableStateOf(null)
		private set

	var title by mutableStateOf("")
		private set

	/** The episode's code and name under a show's title; null for a film. */
	var subtitle: String? by mutableStateOf(null)
		private set

	var isPlaying by mutableStateOf(false)
		private set

	var isBuffering by mutableStateOf(true)
		private set

	/** The title's length in milliseconds; 0 until the server says. */
	var durationMs by mutableLongStateOf(0L)
		private set

	/** What the quick skips so far add up to in seconds, shown over the picture; null when none is pending. */
	var skipping: Int? by mutableStateOf(null)
		private set

	/** The title is over: the screen closes. */
	var finished by mutableStateOf(false)
		private set

	private var plan: PlayPlan? = null
	private var stream: PlayStream? = null

	/** Where the open stream starts in the title, in seconds: 0 for a file, the conversion's offset for the server's stream. */
	private var origin = 0.0
	private var session: String? = null
	private var generation = 0
	private var onDeviceFailed = false
	private var everReady = false
	private var watched = false
	private var reporting = false
	private var lastMs = 0L
	private var ticker: Job? = null
	private var deadline: Job? = null
	private var skipJob: Job? = null
	private val skips = SkipBurst()

	/** The picks made on the menus. They outlive the stream, which opens again for another audio. */
	private var audioPick: Int? = null
	private var subtitlePick: SubtitlePick? = null
	private val remembering = Mutex()

	private val listener = object : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			this@PlayerModel.isPlaying = isPlaying
			report()
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			isBuffering = playbackState == Player.STATE_BUFFERING
			when (playbackState) {
				Player.STATE_READY -> ready()
				Player.STATE_ENDED -> ended()
			}
			report()
		}

		override fun onTracksChanged(available: Tracks) {
			player?.let(tracks::sync)
		}

		override fun onCues(cueGroup: CueGroup) = tracks.onCues(cueGroup.cues)

		override fun onPlayerError(error: PlaybackException) = failed(error)
	}

	init {
		viewModelScope.launch { open(startAt = null) }
	}

	/** Where the title is, in milliseconds: the stream's position plus where the stream starts in the title. */
	fun movieMs(): Long {
		val engine = player ?: return lastMs.takeIf { it > 0 } ?: ((plan?.startAt ?: 0.0) * 1000).toLong()
		lastMs = engine.currentPosition + (origin * 1000).toLong()
		return lastMs
	}

	fun toggle() {
		val engine = player ?: return
		if (engine.playWhenReady) engine.pause() else engine.play()
	}

	fun pause() {
		player?.pause()
	}

	/** Skips by [by] seconds. Quick repeats add up and make one jump once the taps stop. */
	fun skip(by: Int) {
		val burst = skips.tap(by, movieMs() / 1000.0, SystemClock.elapsedRealtime() / 1000.0)
		skipping = burst.total
		skipJob?.cancel()
		skipJob = viewModelScope.launch {
			delay(SKIP_SETTLE_MS)
			seekTo(burst.target)
			delay(SKIP_FEEDBACK_MS)
			skipping = null
		}
	}

	/** Moves to [seconds] into the title. The server's stream is seeked in place when it covers the place, else it restarts there. */
	fun seekTo(seconds: Double) {
		val engine = player ?: return
		val plan = plan ?: return
		val target = if (plan.duration > 0) seconds.coerceIn(0.0, maxOf(0.0, plan.duration - 1)) else maxOf(0.0, seconds)
		val local = target - origin
		val length = engine.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: plan.duration
		if (stream?.kind == PlayStream.StreamKind.File || staysInStream(local, length)) {
			engine.seekTo((maxOf(0.0, local) * 1000).toLong())
		} else {
			viewModelScope.launch { open(startAt = target) }
		}
	}

	/** Plays the audio of stream [id]. The stream opens again where it was, so Core decides the route for that audio. */
	fun pickAudio(id: Int) {
		if (id == tracks.audioId) return
		audioPick = id
		remember(audio = id, subtitle = null)
		reopen()
	}

	/** Shows the subtitle [pick] names, now: a separate text file is loaded and a track inside the stream is selected. */
	fun pickSubtitle(pick: SubtitlePick) {
		val lists = tracks.lists
		val id = when (pick) {
			SubtitlePick.Off -> null
			SubtitlePick.Automatic -> lists.automatic
			else -> pick.id
		}
		subtitlePick = pick
		remember(audio = null, subtitle = pick)
		tracks.show(lists.subtitles.firstOrNull { it.id == id }, player)
	}

	/** Opens the title again from where it was, after a failure. */
	fun retry() = reopen()

	private fun reopen() {
		viewModelScope.launch { open(startAt = movieMs() / 1000.0) }
	}

	private suspend fun open(startAt: Double?) {
		val gen = ++generation
		stopEngine()
		phase = PlayerPhase.Starting
		try {
			val prefs = container.settings.playback(container.session.state.value.user?.uuid).first()
			val capabilities = withContext(Dispatchers.Default) { PlayerCapabilities.read(app) }
			val connectivity = app.getSystemService(ConnectivityManager::class.java)
			val request = StartRequest(
				ratingKey = ratingKey,
				session = session,
				startAt = startAt,
				fromStart = if (startAt == null && fromStart) true else null,
				quality = prefs.quality,
				savesData = prefs.savesData,
				metered = connectivity.isActiveNetworkMetered,
				directPlay = prefs.directPlay,
				capabilities = capabilities,
				network = connectivity.activeNetwork?.networkHandle?.hashCode() ?: 0,
				now = SystemClock.elapsedRealtime() / 1000.0,
				view = screen(),
				onDeviceFailed = if (onDeviceFailed) true else null,
				audio = audioPick,
				subtitle = subtitlePick,
			)
			var chosen = start(request)
			session = chosen.session
			val probe = chosen.probe
			if (chosen.stream == null && probe != null) {
				chosen = start(request.copy(session = chosen.session, probe = measure(probe), now = SystemClock.elapsedRealtime() / 1000.0))
			}
			if (gen != generation) return
			val opens = chosen.stream
			if (opens == null) fail("This video could not be opened.") else play(chosen, opens)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			val kind = (e as? CoreException)?.kind
			Diag.warning(DiagCategory.Player, "play.start.failed", "error" to (kind?.name ?: e.javaClass.simpleName))
			if (gen == generation) {
				fail(if (kind == CoreException.Kind.Unreachable) "The server could not be reached." else "This video could not be opened.")
			}
		}
	}

	private suspend fun start(request: StartRequest): PlayPlan = container.session.onServer { server, remembered ->
		container.core.startPlay(server, remembered, request.copy(memory = kept(server)))
	}

	/** What the profile has kept of this title's track picks, in the form Core reads; null when nothing yet. */
	private suspend fun kept(server: PlexServer): JsonElement? =
		container.settings.trackMemory(memoryKey(server))?.let { runCatching { CoreJson.parseToJsonElement(it) }.getOrNull() }

	private fun memoryKey(server: PlexServer) =
		"tracks.${server.clientIdentifier}.${container.session.state.value.user?.uuid ?: "-"}.$titleKey"

	/** Keeps a hand pick for the title: Core folds it into what the profile remembers, and the app stores that. */
	private fun remember(audio: Int?, subtitle: SubtitlePick?) {
		val chosen = plan ?: return
		// On the app's scope, one at a time: a pick made as the page closes is still kept, and two quick ones fold into each other.
		container.scope.launch {
			remembering.withLock {
				attempt("remember") { server, remembered ->
					val folded = container.core.playRemember(server, remembered, chosen.session, audio, subtitle, kept(server))
					container.settings.rememberTracks(memoryKey(server), CoreJson.encodeToString(JsonElement.serializer(), folded))
				}
			}
		}
	}

	/** A call to the server whose failure is logged, not thrown: playback goes on without it. Null when it failed. */
	private suspend fun <T : Any> attempt(name: String, call: suspend (PlexServer, String?) -> T): T? = try {
		container.session.onServer(call)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Diag.debug(DiagCategory.Player, "$name.failed", "error" to ((e as? CoreException)?.kind?.name ?: e.javaClass.simpleName))
		null
	}

	/** The timed read Core asks for before it picks a quality: the bytes that came and the seconds they took, zeros when it failed. */
	private suspend fun measure(probe: LinkProbe): Reading = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(probe.url)
			.header("Range", "bytes=0-${probe.maxBytes - 1}")
			.apply { probe.headers.forEach { (name, value) -> header(name, value) } }
			.build()
		val call = http.newCall(request)
		call.timeout().timeout((probe.timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
		val started = SystemClock.elapsedRealtimeNanos()
		try {
			call.execute().use { response ->
				val buffer = ByteArray(32 * 1024)
				var bytes = 0
				response.body?.byteStream()?.use { input ->
					while (bytes < probe.maxBytes) {
						val read = input.read(buffer, 0, minOf(buffer.size, probe.maxBytes - bytes))
						if (read < 0) break
						bytes += read
						if ((SystemClock.elapsedRealtimeNanos() - started) / NANOS >= probe.windowSeconds) break
					}
				}
				Reading(bytes, (SystemClock.elapsedRealtimeNanos() - started) / NANOS)
			}
		} catch (e: IOException) {
			Reading(0, 0.0)
		}
	}

	private fun play(chosen: PlayPlan, opens: PlayStream) {
		plan = chosen
		stream = opens
		val hls = opens.kind == PlayStream.StreamKind.Hls
		origin = if (hls) opens.offset else 0.0
		everReady = false
		title = chosen.item.grandparentTitle ?: chosen.item.title
		subtitle = if (chosen.item.grandparentTitle == null) null else listOfNotNull(chosen.item.episodeCode, chosen.item.title).joinToString(" · ")
		durationMs = (chosen.duration * 1000).toLong()
		tracks.begin(chosen, isFile = !hls)
		val engine = factory.create(opens.headers, PlaybackFactory.Kind.Player)
		engine.addListener(listener)
		val source = factory.source(opens.headers, opens.url, hls)
		if (hls) engine.setMediaSource(source) else engine.setMediaSource(source, (chosen.startAt * 1000).toLong())
		engine.playWhenReady = true
		engine.prepare()
		player = engine
		phase = PlayerPhase.Playing
		Diag.info(
			DiagCategory.Player, "play.open",
			"route" to chosen.route.kind.name, "stream" to opens.kind.name, "quality" to chosen.quality, "reasons" to chosen.route.reasons.size,
		)
		startTicker()
		if (hls) {
			deadline = viewModelScope.launch {
				delay(START_DEADLINE_MS)
				if (!everReady) fail("The server took too long to start the video.")
			}
		}
	}

	private fun ready() {
		if (everReady) return
		everReady = true
		deadline?.cancel()
		val engine = player ?: return
		val chosen = plan ?: return
		val opens = stream ?: return
		if (opens.kind != PlayStream.StreamKind.Hls) return
		// A playlist that lists the whole title is on the title's own clock, so it starts where the person was.
		val length = engine.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: return
		if (chosen.duration > 0 && length >= chosen.duration - WHOLE_TITLE_SLACK) {
			origin = 0.0
			if (opens.offset >= WHOLE_TITLE_SLACK) engine.seekTo((opens.offset * 1000).toLong())
		}
	}

	private fun ended() {
		markWatched()
		finished = true
	}

	private fun failed(error: PlaybackException) {
		val chosen = plan
		Diag.warning(DiagCategory.Player, "play.failed", "code" to error.errorCodeName, "route" to chosen?.route?.kind?.name)
		if (chosen?.route?.kind == RouteKind.DirectPlay && !onDeviceFailed) {
			// The tablet could not play the file itself: the server converts it, from where it stopped.
			onDeviceFailed = true
			val at = movieMs() / 1000.0
			viewModelScope.launch { open(startAt = at) }
		} else {
			fail("This video could not be played.")
		}
	}

	private fun fail(message: String) {
		// Reads the position while the player still exists, so Try Again and the final stop know where it was.
		movieMs()
		stopEngine()
		phase = PlayerPhase.Failed(message)
	}

	private fun stopEngine() {
		deadline?.cancel()
		ticker?.cancel()
		skipJob?.cancel()
		player?.let {
			it.removeListener(listener)
			it.release()
		}
		player = null
		tracks.stop()
		isPlaying = false
		isBuffering = true
		skipping = null
	}

	private fun startTicker() {
		ticker?.cancel()
		ticker = viewModelScope.launch {
			while (true) {
				delay(REPORT_INTERVAL_MS)
				report()
				val chosen = plan
				if (chosen != null && chosen.duration > 0 && movieMs() >= chosen.duration * WATCHED_AT * 1000) markWatched()
			}
		}
	}

	/** Tells the server where playback is. Core decides how often that really goes out; one report is in flight at a time. */
	private fun report() {
		val chosen = plan ?: return
		val engine = player ?: return
		if (reporting) return
		val state = when {
			engine.playbackState == Player.STATE_BUFFERING -> "buffering"
			engine.isPlaying -> "playing"
			else -> "paused"
		}
		val time = movieMs().toInt()
		reporting = true
		viewModelScope.launch {
			try {
				attempt("report") { server, remembered ->
					container.core.playReport(server, remembered, chosen.session, state, time, (chosen.duration * 1000).toInt())
				}
			} finally {
				reporting = false
			}
		}
	}

	private fun markWatched() {
		val chosen = plan ?: return
		if (watched) return
		watched = true
		// On the app's scope: the screen closes the moment a title ends, and its own scope goes with it.
		container.scope.launch {
			attempt("watched") { server, remembered -> container.core.setWatched(server, remembered, chosen.item.ratingKey, true) }
			container.changes.record(keysOf(chosen))
		}
	}

	private fun keysOf(chosen: PlayPlan) = setOfNotNull(chosen.item.ratingKey, chosen.item.parentRatingKey, chosen.item.grandparentRatingKey)

	private fun screen(): Frame {
		val metrics = app.resources.displayMetrics
		return Frame(maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels))
	}

	override fun onCleared() {
		val chosen = plan
		val time = movieMs().toInt()
		generation++
		stopEngine()
		if (chosen == null) return
		val keys = keysOf(chosen)
		// The page is gone, so its scope is too: the app's own scope tells the server the title stopped.
		container.scope.launch {
			attempt("stop") { server, remembered ->
				container.core.playStop(server, remembered, chosen.session, time, (chosen.duration * 1000).toInt(), close = true)
			}
			container.changes.record(keys)
		}
	}

	private companion object {
		const val NANOS = 1_000_000_000.0
		const val REPORT_INTERVAL_MS = 1000L
		const val START_DEADLINE_MS = 30_000L
		const val SKIP_SETTLE_MS = 400L
		const val SKIP_FEEDBACK_MS = 700L

		/** The share of the title after which it counts as watched. */
		const val WATCHED_AT = 0.9

		/** How far short of the title's length a playlist can fall and still be the whole title. */
		const val WHOLE_TITLE_SLACK = 30.0
	}
}

/** Quick taps of the skip buttons add up: three of +10 within 1.5 s of each other are one jump of +30 from where the first began. */
internal class SkipBurst {
	private var base = 0.0
	private var total = 0
	private var last = Double.NEGATIVE_INFINITY

	/** One tap of [by] seconds at [now] with the title at [position], both in seconds. */
	fun tap(by: Int, position: Double, now: Double): Skip {
		if (now - last > WINDOW) {
			base = position
			total = 0
		}
		last = now
		total += by
		return Skip(total, base + total)
	}

	private companion object {
		const val WINDOW = 1.5
	}
}

internal data class Skip(val total: Int, val target: Double)

/** Whether [localSeconds] is inside a stream [lengthSeconds] long, so a seek can stay in it. */
internal fun staysInStream(localSeconds: Double, lengthSeconds: Double): Boolean =
	localSeconds >= 0 && localSeconds <= lengthSeconds - 1

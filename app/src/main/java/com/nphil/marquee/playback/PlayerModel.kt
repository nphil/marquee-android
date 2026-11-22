package com.nphil.marquee.playback

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nphil.marquee.AppContainer
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.core.AtCredits
import com.nphil.marquee.core.AutoplayPlan
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.FormatBadge
import com.nphil.marquee.core.Frame
import com.nphil.marquee.core.LinkProbe
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.Reading
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.SkipSide
import com.nphil.marquee.core.StartRequest
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.core.TapAction
import com.nphil.marquee.core.TranscodeState
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.core.playAutoplay
import com.nphil.marquee.core.playNeighbors
import com.nphil.marquee.core.playRemember
import com.nphil.marquee.core.playReport
import com.nphil.marquee.core.playStop
import com.nphil.marquee.core.playTap
import com.nphil.marquee.core.playTapReset
import com.nphil.marquee.core.playTranscode
import com.nphil.marquee.core.setWatched
import com.nphil.marquee.core.startPlay
import com.nphil.marquee.data.PlaybackPrefs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
	private var ratingKey: String,
	/** What the profile's remembered tracks are filed under: the show for an episode, else the title itself. */
	private var titleKey: String,
	private var fromStart: Boolean,
) : ViewModel() {
	private val app = context.applicationContext
	private val factory = PlaybackFactory(app, http)

	/** The audio and subtitle tracks the menus list, and the subtitle on screen. */
	internal val tracks = PlayerTracks(viewModelScope, http, ::movieMs)

	/** Skip Intro and Up Next over the picture. */
	internal val offers = PlayerOffers(viewModelScope, container.core, ::steady, { menusOpen || scrubbing }, ::seekTo, ::playNext)

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

	/** The episode after this one, once the play queue names it; null for a film or a last episode. */
	var next: PlexItem? by mutableStateOf(null)
		private set

	/** Seconds the skip buttons and a double-tap move, from the profile's settings. */
	var skipInterval by mutableIntStateOf(PlaybackPrefs().skipInterval)
		private set

	/** Taps that call for the controls: true shows or hides them, false only brings them back. */
	val controlTaps: Flow<Boolean> get() = controls.receiveAsFlow()

	/** A menu is open over the picture: it holds an Up Next countdown back. */
	var menusOpen = false
		set(value) {
			field = value
			if (value) offers.interrupt()
		}

	/** The seek bar is being dragged. */
	var scrubbing = false
		set(value) {
			field = value
			if (value) offers.interrupt()
		}

	/** How the title plays: the route, the tracks, the marks and the chapters. Null until the server has chosen. */
	var plan: PlayPlan? by mutableStateOf(null)
		private set
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

	/** The profile's playback settings as the title opened. */
	private var prefs = PlaybackPrefs()
	private val taps = Channel<Tap>(Channel.UNLIMITED)
	private val controls = Channel<Boolean>(Channel.CONFLATED)
	private var singleTap: Job? = null

	/** The title whose play queue was last asked for its next episode. */
	private var neighborsOf: String? = null

	/** The picks made on the menus. They outlive the stream, which opens again for another audio. */
	private var audioPick: Int? = null
	private var subtitlePick: SubtitlePick? = null
	private val remembering = Mutex()

	/** The quality picked on the menu for this title; the profile's applies until one is. */
	private var qualityPick: Int? = null

	/** The quality rung the title plays at, for the menu. */
	var quality by mutableIntStateOf(PlaybackPrefs().quality)
		private set

	/** The playback speed, 0.5 to 2; every stream the title opens plays at it. */
	var speed by mutableFloatStateOf(1f)
		private set

	private var sleepsAtEnd by mutableStateOf(false)

	/** Stop when this title ends: no Up Next and no autoplay. */
	var sleepAtEnd: Boolean
		get() = sleepsAtEnd
		set(value) {
			sleepsAtEnd = value
			offers.follow(if (value) null else next)
			Diag.info(DiagCategory.Player, "sleep.end", "on" to value)
		}

	/** Pictures for the seek bar from the open stream; null when it offers none. */
	var previews: ScrubPreviews? by mutableStateOf(null)
		private set

	/** The badge of the format that started, until the screen has taken it. */
	var pendingBadge: FormatBadge? by mutableStateOf(null)
		private set

	/** The title the badge was last made for: it shows once per title, not on each restart of the stream. */
	private var badgedKey: String? = null

	/** The stats overlay is up. */
	var showsStats by mutableStateOf(false)
		private set

	// How the open stream is keeping up, for the stats overlay and the diagnostics.
	private val engineStats = EngineStats()
	private var stalls = 0
	private var stalledAt = 0L
	private var seekedAt = 0L
	private var openedAt = 0L
	private var plannedAt = 0L
	private var readyAt = 0L
	private var ticks = 0

	/** The server's own account of the conversion it runs for this stream; null for a file. */
	private var transcode: TranscodeState? = null

	/** Why the title plays on the server although the tablet was to play it; null when it does not. */
	private var fallback: String? = null

	private val listener = object : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			this@PlayerModel.isPlaying = isPlaying
			offers.stateChanged(isPlaying)
			report()
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			isBuffering = playbackState == Player.STATE_BUFFERING
			watchStall(playbackState)
			when (playbackState) {
				Player.STATE_READY -> ready()
				Player.STATE_ENDED -> ended()
			}
			offers.stateChanged(isPlaying)
			report()
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			Diag.info(
				DiagCategory.Player, if (playWhenReady) "play" else "pause",
				"reason" to playReason(reason), "at" to movieMs() / 1000.0,
			)
		}

		override fun onTracksChanged(available: Tracks) {
			player?.let(tracks::sync)
		}

		override fun onCues(cueGroup: CueGroup) = tracks.onCues(cueGroup.cues)

		override fun onPlayerError(error: PlaybackException) = failed(error)
	}

	init {
		Diag.beginPlayback()
		viewModelScope.launch { open(startAt = null) }
		viewModelScope.launch { for (tap in taps) answerTap(tap) }
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

	/** Plays at [value] times the speed, 0.5 to 2, and keeps it for every stream the title opens. */
	@JvmName("pickSpeed")
	fun setSpeed(value: Float) {
		speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
		player?.setPlaybackSpeed(speed)
		Diag.info(DiagCategory.Player, "speed", "to" to speed)
	}

	/** Plays the title at the quality [preset] from where it is: the stream opens again. */
	@JvmName("pickQuality")
	fun setQuality(preset: Int) {
		if (preset == quality) return
		qualityPick = preset
		quality = preset
		Diag.info(DiagCategory.Player, "quality.pick", "to" to preset)
		reopen()
	}

	fun toggleStats() {
		showsStats = !showsStats
		Diag.info(DiagCategory.Player, "stats", "shown" to showsStats)
	}

	/** The stats overlay's numbers as they are now. */
	fun stats(): Stats {
		val chosen = plan ?: return Stats(emptyList(), emptyList())
		val reading = player?.let { engineStats.read(app, it, stalls) } ?: EngineReading(stalls = stalls)
		val start = startSummary(plannedAt.takeIf { it > 0 }?.minus(openedAt), readyAt.takeIf { it > 0 }?.minus(openedAt))
		return Stats(streamLines(chosen, fallback, reading), healthLines(start, reading, transcode?.words(), systemLines(app)))
	}

	/** The stats overlay as text for a bug report. */
	fun statsReport(): String = reportText(BuildConfig.VERSION_NAME, title, stats().all)

	/** How the title plays and why, for the Playback Info page. */
	fun info(): List<StatSection> {
		val chosen = plan ?: return emptyList()
		val audioLabel = tracks.lists.audio.firstOrNull { it.id == tracks.audioId }?.label
		val subtitleLabel = tracks.lists.subtitles.firstOrNull { it.id == tracks.subtitleId }?.label
		return infoSections(chosen, fallback, audioLabel, subtitleLabel)
	}

	/** The screen has taken the format badge. */
	fun badgeTaken() {
		pendingBadge = null
	}

	/**
	 * The app went behind another or the screen turned off ([on]), or came back. With Keep Playing the picture is dropped
	 * and the sound goes on under the media session; without it playback pauses.
	 */
	fun background(on: Boolean) {
		val engine = player ?: return
		if (on && !prefs.keepsPlaying) {
			engine.pause()
			return
		}
		engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build()
		Diag.info(DiagCategory.Player, if (on) "background" else "foreground", "playing" to engine.isPlaying)
	}

	/** Skips by [by] seconds. Quick repeats add up and make one jump once the taps stop. */
	fun skip(by: Int) {
		offers.interrupt()
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

	/**
	 * Moves to [seconds] into the title. The server's stream is seeked in place when it covers the place, else it restarts
	 * there. False when nothing could move.
	 */
	fun seekTo(seconds: Double): Boolean {
		val engine = player
		val plan = plan
		if (engine == null || plan == null) {
			Diag.info(DiagCategory.Player, "seek.refused", "to" to seconds, "why" to if (engine == null) "no player" else "no plan")
			return false
		}
		val target = if (plan.duration > 0) seconds.coerceIn(0.0, maxOf(0.0, plan.duration - 1)) else maxOf(0.0, seconds)
		val local = target - origin
		val length = engine.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: plan.duration
		val inStream = stream?.kind == PlayStream.StreamKind.File || staysInStream(local, length)
		seekedAt = SystemClock.elapsedRealtime()
		Diag.info(DiagCategory.Player, "seek", "from" to movieMs() / 1000.0, "to" to target, "restarts" to !inStream)
		if (inStream) {
			engine.seekTo((maxOf(0.0, local) * 1000).toLong())
		} else {
			viewModelScope.launch { open(startAt = target) }
		}
		return true
	}

	/** Plays the audio of stream [id]. The stream opens again where it was, so Core decides the route for that audio. */
	fun pickAudio(id: Int) {
		if (id == tracks.audioId) return
		Diag.info(DiagCategory.Audio, "pick", "id" to id, "label" to tracks.lists.audio.firstOrNull { it.id == id }?.label)
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
		val picked = lists.subtitles.firstOrNull { it.id == id }
		Diag.info(DiagCategory.Subtitles, "pick", "id" to id, "delivery" to picked?.delivery, "external" to picked?.isExternal)
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
		stopEngine(keepService = true)
		phase = PlayerPhase.Starting
		openedAt = SystemClock.elapsedRealtime()
		plannedAt = 0
		readyAt = 0
		try {
			prefs = container.settings.playback(container.session.state.value.user?.uuid).first()
			skipInterval = prefs.skipInterval
			val capabilities = withContext(Dispatchers.Default) { PlayerCapabilities.read(app) }
			quality = qualityPick ?: prefs.quality
			val connectivity = app.getSystemService(ConnectivityManager::class.java)
			val request = StartRequest(
				ratingKey = ratingKey,
				session = session,
				startAt = startAt,
				fromStart = if (startAt == null && fromStart) true else null,
				quality = quality,
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
			Diag.info(
				DiagCategory.Player, "play.request",
				"item" to ratingKey, "startAt" to startAt, "quality" to request.quality, "directPlay" to request.directPlay,
				"metered" to request.metered, "savesData" to request.savesData, "onDeviceFailed" to request.onDeviceFailed,
				"screen" to "${request.view?.width}x${request.view?.height}",
				"hevc" to capabilities.hevc, "hevcMain10" to capabilities.hevcMain10, "av1" to capabilities.av1,
				"hdrDisplay" to capabilities.hdrDisplay, "dolbyVision" to capabilities.dolbyVisionProfiles.joinToString(","),
				"audio" to capabilities.nativeAudioCodecs.joinToString(","),
			)
			var chosen = start(request)
			session = chosen.session
			val probe = chosen.probe
			if (chosen.stream == null && probe != null) {
				val (reading, answerMs) = measure(probe)
				val kbps = if (reading.seconds > 0) (reading.bytes * 8L / 1000 / reading.seconds).toInt() else 0
				Diag.info(
					DiagCategory.Link, "probe",
					"bytes" to reading.bytes, "seconds" to reading.seconds, "kbps" to kbps, "answerMs" to answerMs,
				)
				chosen = start(request.copy(session = chosen.session, probe = reading, now = SystemClock.elapsedRealtime() / 1000.0))
			}
			val rule = autoplayRule(creditsWatched = false)
			if (gen != generation) return
			val opens = chosen.stream
			if (opens == null) fail("This video could not be opened.") else play(chosen, opens, rule)
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

	/**
	 * The timed read Core asks for before it picks a quality, with the milliseconds the server took to answer; zeros when it
	 * did not answer in time. The clock follows [LinkRead], so a slow answer is not mistaken for a slow link.
	 */
	private suspend fun measure(probe: LinkProbe): Pair<Reading, Long> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(probe.url)
			.header("Range", "bytes=0-${probe.maxBytes - 1}")
			.apply { probe.headers.forEach { (name, value) -> header(name, value) } }
			.build()
		val call = http.newCall(request)
		// The server has timeoutSeconds to answer; the read then runs on for the window from its first chunk.
		call.timeout().timeout(((probe.timeoutSeconds + probe.windowSeconds) * 1000).toLong(), TimeUnit.MILLISECONDS)
		val read = LinkRead(probe.maxBytes, probe.windowSeconds)
		val asked = SystemClock.elapsedRealtimeNanos() / NANOS
		var answered = 0L
		try {
			call.execute().use { response ->
				val buffer = ByteArray(32 * 1024)
				response.body?.byteStream()?.use { input ->
					var done = false
					while (!done) {
						val count = input.read(buffer, 0, buffer.size)
						if (count < 0) break
						val now = SystemClock.elapsedRealtimeNanos() / NANOS
						if (!read.started) answered = ((now - asked) * 1000).toLong()
						done = read.received(count, now)
					}
				}
			}
			Reading(read.after, read.seconds) to answered
		} catch (e: IOException) {
			Reading(0, 0.0) to ((SystemClock.elapsedRealtimeNanos() / NANOS - asked) * 1000).toLong()
		}
	}

	private fun play(chosen: PlayPlan, opens: PlayStream, rule: AutoplayPlan) {
		plan = chosen
		stream = opens
		val hls = opens.kind == PlayStream.StreamKind.Hls
		origin = if (hls) opens.offset else 0.0
		everReady = false
		plannedAt = SystemClock.elapsedRealtime()
		seekedAt = plannedAt
		stalls = 0
		stalledAt = 0
		ticks = 0
		transcode = null
		title = chosen.item.grandparentTitle ?: chosen.item.title
		subtitle = if (chosen.item.grandparentTitle == null) null else listOfNotNull(chosen.item.episodeCode, chosen.item.title).joinToString(" · ")
		durationMs = (chosen.duration * 1000).toLong()
		tracks.begin(chosen, isFile = !hls)
		offers.configure(chosen, prefs.introSkipDelay, rule)
		loadNeighbors(chosen)
		val engine = factory.create(opens.headers, PlaybackFactory.Kind.Player)
		engine.addListener(listener)
		engineStats.reset()
		engine.addAnalyticsListener(engineStats)
		engine.setPlaybackSpeed(speed)
		engine.setWakeMode(C.WAKE_MODE_NETWORK)
		val source = factory.source(opens.headers, opens.url, hls, mediaMetadata(chosen))
		if (hls) engine.setMediaSource(source) else engine.setMediaSource(source, (chosen.startAt * 1000).toLong())
		engine.playWhenReady = true
		engine.prepare()
		player = engine
		phase = PlayerPhase.Playing
		if (prefs.keepsPlaying) container.playback.publish(app, engine)
		previews = ScrubPreviewsFactory.create(app, viewModelScope, http, chosen, opens, { movieMs() / 1000.0 }, { isBuffering })
		if (badgedKey != chosen.item.ratingKey) {
			badgedKey = chosen.item.ratingKey
			pendingBadge = chosen.badge.takeUnless { it.isEmpty }
		}
		logOpen(chosen, opens)
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
		readyAt = SystemClock.elapsedRealtime()
		Diag.info(DiagCategory.Player, "started", "planMs" to plannedAt - openedAt, "readyMs" to readyAt - openedAt)
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
		val upcoming = next.takeUnless { sleepsAtEnd }
		Diag.notice(
			DiagCategory.Player, "ended",
			"at" to movieMs() / 1000.0, "next" to upcoming?.ratingKey, "countdown" to offers.upNextCounting,
		)
		when {
			upcoming == null -> finish()
			offers.upNextCounting -> {
				markWatched()
				go(upcoming)
			}
			else -> viewModelScope.launch {
				if (autoplayRule(offers.creditsDismissed).playsNextAtEnd) {
					markWatched()
					go(upcoming)
				} else {
					finish()
				}
			}
		}
	}

	private fun finish() {
		markWatched()
		offers.stop()
		finished = true
	}

	/** What the profile's autoplay rule does at the credits and at the end. A failed call leaves the card to a tap. */
	private suspend fun autoplayRule(creditsWatched: Boolean): AutoplayPlan = try {
		container.core.playAutoplay(prefs.autoplaysNext, prefs.creditsAutoplay, prefs.nextEpisodeDelay, creditsWatched)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Diag.warning(DiagCategory.Offers, "autoplay.failed", "error" to e.javaClass.simpleName)
		AutoplayPlan(AtCredits.Card, null, playsNextAtEnd = false)
	}

	/** The title is playing steadily: a stream is open, not paused and not waiting for data. */
	private fun steady() = phase == PlayerPhase.Playing && player != null && isPlaying && !isBuffering

	/** The viewer did something: an Up Next countdown stops for good. */
	fun userActed() = offers.interrupt()

	/** Plays the episode after this one, if the queue has one. */
	fun playNext() {
		val upcoming = next ?: return
		// Going on from the credits counts as having watched the episode.
		if (offers.offer is Offer.UpNext) markWatched()
		go(upcoming)
	}

	/** Plays [target] in this player's place: the title stops where it is, and the session and its queue carry on. */
	private fun go(target: PlexItem) {
		val chosen = plan
		val time = movieMs().toInt()
		Diag.notice(DiagCategory.Player, "go", "item" to target.ratingKey, "from" to ratingKey, "at" to time / 1000.0)
		Diag.endPlayback()
		Diag.beginPlayback()
		generation++
		stopEngine(keepService = true)
		if (chosen != null) {
			val keys = keysOf(chosen)
			container.scope.launch {
				attempt("stop") { server, remembered ->
					container.core.playStop(server, remembered, chosen.session, time, (chosen.duration * 1000).toInt(), close = false)
				}
				container.changes.record(keys)
			}
		}
		ratingKey = target.ratingKey
		titleKey = target.grandparentRatingKey ?: target.ratingKey
		fromStart = false
		plan = null
		stream = null
		origin = 0.0
		lastMs = 0
		everReady = false
		onDeviceFailed = false
		watched = false
		fallback = null
		sleepsAtEnd = false
		audioPick = null
		subtitlePick = null
		next = null
		offers.reset()
		viewModelScope.launch { open(startAt = null) }
	}

	/** Asks the play queue for the episode that follows, once per title: a restart of the same title keeps the answer. */
	private fun loadNeighbors(chosen: PlayPlan) {
		if (neighborsOf == chosen.item.ratingKey) return
		neighborsOf = chosen.item.ratingKey
		next = null
		offers.follow(null)
		if (chosen.item.type != MediaType.Episode) return
		viewModelScope.launch {
			val found = attempt("queue") { server, remembered -> container.core.playNeighbors(server, remembered, chosen.session) }
			if (neighborsOf != chosen.item.ratingKey) return@launch
			next = found?.next
			offers.follow(found?.next.takeUnless { sleepsAtEnd })
		}
	}

	private class Tap(val x: Double, val width: Double, val at: Double)

	/** A tap on the picture at [x] of [width]: Core tells a double-tap that skips from a single tap that shows the controls. */
	fun tap(x: Float, width: Float) {
		taps.trySend(Tap(x.toDouble(), width.toDouble(), SystemClock.elapsedRealtime() / 1000.0))
	}

	private suspend fun answerTap(tap: Tap) {
		offers.interrupt()
		val id = plan?.session ?: return
		singleTap?.cancel()
		if (isBuffering) {
			// While the picture waits for data a tap only brings the controls, and never skips.
			quietly("tap.reset") { container.core.playTapReset(id) }
			controls.trySend(false)
			return
		}
		val answer = quietly("tap") { container.core.playTap(id, prefs.skipTaps, tap.x, tap.width, tap.at) } ?: return
		when (answer.action) {
			TapAction.Skip -> skip(if (answer.side == SkipSide.Back) -skipInterval else skipInterval)
			TapAction.Controls -> controls.trySend(true)
			TapAction.Single -> singleTap = viewModelScope.launch {
				delay(((answer.window ?: 0.0) * 1000).toLong())
				controls.trySend(true)
			}
		}
	}

	/** A bridge call that needs no server, whose failure is logged, not thrown. Null when it failed. */
	private suspend fun <T : Any> quietly(name: String, call: suspend () -> T): T? = try {
		call()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Diag.debug(DiagCategory.Player, "$name.failed", "error" to ((e as? CoreException)?.kind?.name ?: e.javaClass.simpleName))
		null
	}

	private fun failed(error: PlaybackException) {
		val chosen = plan
		Diag.warning(DiagCategory.Player, "play.failed", "code" to error.errorCodeName, "route" to chosen?.route?.kind?.name)
		if (chosen?.route?.kind == RouteKind.DirectPlay && !onDeviceFailed) {
			// The tablet could not play the file itself: the server converts it, from where it stopped.
			onDeviceFailed = true
			fallback = "The tablet could not play the file (${error.errorCodeName}); the server converts it from where it stopped."
			val at = movieMs() / 1000.0
			Diag.notice(DiagCategory.Player, "fallback", "code" to error.errorCodeName, "at" to at)
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

	private fun stopEngine(keepService: Boolean = false) {
		deadline?.cancel()
		ticker?.cancel()
		skipJob?.cancel()
		previews?.close()
		previews = null
		transcode = null
		stalledAt = 0
		// The media session goes before its player does.
		container.playback.retract(app, stopService = !keepService)
		player?.let {
			it.removeListener(listener)
			it.removeAnalyticsListener(engineStats)
			it.release()
		}
		player = null
		tracks.stop()
		isPlaying = false
		isBuffering = true
		offers.stateChanged(false)
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
				offers.tick(movieMs() / 1000.0)
				ticks++
				if (stream?.kind == PlayStream.StreamKind.Hls && (ticks == 2 || ticks % TRANSCODE_EVERY == 0)) pollTranscode()
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

	/** A stall is the picture waiting for data after it had started, apart from the wait that follows a seek. */
	private fun watchStall(state: Int) {
		val engine = player ?: return
		val now = SystemClock.elapsedRealtime()
		if (state == Player.STATE_BUFFERING) {
			if (!beginsStall(everReady, stalledAt != 0L, now, seekedAt)) return
			stalledAt = now
			stalls++
			Diag.warning(DiagCategory.Player, "stall", *stallFields(engine, "n" to stalls))
		} else if (state == Player.STATE_READY && stalledAt != 0L) {
			val waited = now - stalledAt
			stalledAt = 0
			Diag.warning(DiagCategory.Player, "stall.recovered", *stallFields(engine, "waitedMs" to waited))
		}
	}

	/** What says whether a stall is the link's or the server's: the buffer, the link, the segments and the conversion's speed. */
	private fun stallFields(engine: ExoPlayer, vararg first: Pair<String, Any?>): Array<Pair<String, Any?>> {
		val loads = engineStats.loads()
		val reading = engineStats.read(app, engine, stalls)
		return arrayOf<Pair<String, Any?>>(
			*first,
			"at" to movieMs() / 1000.0,
			"bufferedMs" to engine.totalBufferedDuration,
			"estimateKbps" to reading.networkBps / 1000,
			"streamKbps" to reading.streamBps / 1000,
			"loads" to loads.count,
			"loadKbps" to loads.kbps,
			"realtime" to loads.realtime,
			"serverSpeed" to transcode?.speed,
		)
	}

	/** Asks the server what it does with the stream it converts: whether it copies or re-encodes, burns a subtitle in, and keeps up. */
	private fun pollTranscode() {
		val id = plan?.session ?: return
		val gen = generation
		viewModelScope.launch {
			val state = quietly("transcode") { container.core.playTranscode(id) } ?: return@launch
			if (gen != generation) return@launch
			val before = transcode
			transcode = state
			if (!state.active) return@launch
			val changed = before == null || before.video != state.video || before.audio != state.audio || before.subtitle != state.subtitle
			val fields = arrayOf<Pair<String, Any?>>(
				"video" to state.video, "audio" to state.audio, "subtitle" to state.subtitle, "speed" to state.speed,
				"throttled" to state.throttled, "fullPipeline" to state.fullPipeline, "decoding" to state.decoding, "encoding" to state.encoding,
			)
			when {
				// The server drawing a subtitle into the picture is what the app asks it never to do.
				changed && state.subtitle == "burn" -> Diag.warning(DiagCategory.Player, "transcode.burn", *fields)
				changed -> Diag.info(DiagCategory.Player, "transcode", *fields)
				else -> Diag.debug(DiagCategory.Player, "transcode.speed", *fields)
			}
		}
	}

	/** What the media session's notification says: the episode's name under the show's, or the film's. */
	private fun mediaMetadata(chosen: PlayPlan): MediaMetadata =
		MediaMetadata.Builder().setTitle(chosen.item.title).setArtist(chosen.item.grandparentTitle).build()

	/** Says how the title opens and why: the route, each reason Core gave for it, and the tracks that play. */
	private fun logOpen(chosen: PlayPlan, opens: PlayStream) {
		val route = chosen.route
		val audio = chosen.audioStream()
		Diag.info(
			DiagCategory.Player, "play.open",
			"route" to route.kind.name, "stream" to opens.kind.name, "quality" to chosen.quality, "adapts" to chosen.adapts,
			"reasons" to route.reasons.size, "why" to route.reasons.joinToString("; "),
			"reencodes" to route.reencodesVideo, "maxKbps" to route.maxVideoBitrateKbps, "resolution" to route.videoResolution,
			"video" to chosen.videoStream()?.formatSummary(),
			"audio" to listOfNotNull(route.audio?.toString(), audio?.codec, audio?.channels?.let { "${it}ch" }).joinToString(" "),
			"subtitle" to listOfNotNull(route.subtitle.kind, route.subtitle.track?.toString(), tracks.subtitleId?.let { "shown $it" }).joinToString(" "),
		)
	}

	private fun markWatched() {
		val chosen = plan ?: return
		if (watched) return
		watched = true
		Diag.info(DiagCategory.Player, "watched", "item" to chosen.item.ratingKey, "at" to movieMs() / 1000.0)
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
		Diag.endPlayback()
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

		/** The server's account of its conversion is asked for every this many ticks of the report loop. */
		const val TRANSCODE_EVERY = 5
		const val MIN_SPEED = 0.5f
		const val MAX_SPEED = 2f
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

/** A buffering that begins this soon after a seek is the seek, not a stall. */
private const val SEEK_WINDOW_MS = 1500L

/** Whether a buffering that begins at [now] is a stall: the picture had started, none is already counted, and no seek began just before. */
internal fun beginsStall(started: Boolean, counted: Boolean, now: Long, seekedAt: Long): Boolean =
	started && !counted && now - seekedAt >= SEEK_WINDOW_MS

private fun playReason(reason: Int): String = when (reason) {
	Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
	Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus"
	Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones out"
	Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
	Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end"
	Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed"
	else -> "other"
}

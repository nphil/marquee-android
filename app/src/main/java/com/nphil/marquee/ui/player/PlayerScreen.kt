package com.nphil.marquee.ui.player

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.Chapter
import com.nphil.marquee.core.Marker
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.data.SubtitleLook
import com.nphil.marquee.playback.HideSystemBars
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.PlayerPhase
import com.nphil.marquee.cast.CastProvider
import com.nphil.marquee.ui.cast.CastChooser
import com.nphil.marquee.ui.cast.LookForCastDevices
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** How long the controls stay up after the last touch while the title plays, then they hide: a bright overlay does not sit on the picture. */
private const val CONTROLS_DWELL_MS = 3_000L

/** How long an offer stays up with the controls away, then it hides and comes back with them: a bright card does not sit in one place. */
private const val OFFER_DWELL_MS = 10_000L

/** How far above the bottom edge the offer sits, clear of the seek bar. */
private val OFFER_LIFT = CHROME_BOTTOM_CLEARANCE

private const val POSITION_POLL_MS = 250L

/** The speed while the picture is held. */
private const val HOLD_SPEED = 2f

/** How long the way out of the lock stays after a touch on the locked screen, then it fades. */
private const val UNLOCK_DWELL_MS = 2_500L

/** How long the zoom and lock notes stay up. */
private const val ASPECT_NOTE_MS = 1_000L
private const val LOCK_NOTE_MS = 1_200L

/** Below the top bar, where the notes sit. */
private val NOTE_TOP = 72.dp

/** A finished scrub is held on the bar until the player is within this of it, or this long has passed. */
private const val HOLD_NEAR_MS = 2_000L
private const val HOLD_MAX_MS = 8_000L

/** How the picture fills the window. */
@OptIn(UnstableApi::class)
internal enum class PlayerAspect(val label: String, val detail: String, val resizeMode: Int) {
	Fit("Fit", "The whole picture, with bars where it does not fill the window", AspectRatioFrameLayout.RESIZE_MODE_FIT),
	Fill("Fill", "Fills the window and crops what does not fit", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
	Stretch("Stretch", "Fills the window and stretches the picture to do it", AspectRatioFrameLayout.RESIZE_MODE_FILL),
	;

	fun next(): PlayerAspect = entries[(ordinal + 1) % entries.size]
}

/**
 * Where a finished scrub was sent, until the player has got there. A stream that restarts at the new place reports the
 * old position for a moment; the bar stays on the target instead of jumping back.
 */
internal class PositionHold {
	private var target = NONE
	private var since = 0L

	fun hold(ms: Long, now: Long) {
		target = ms
		since = now
	}

	fun release() {
		target = NONE
	}

	/** The position to show when the player says [actual] at [now]. */
	fun shown(actual: Long, now: Long): Long {
		if (target == NONE) return actual
		if (abs(actual - target) <= HOLD_NEAR_MS || now - since > HOLD_MAX_MS) {
			target = NONE
			return actual
		}
		return target
	}

	private companion object {
		const val NONE = -1L
	}
}

/** Where the bar is cut, in milliseconds: the start of each chapter and the edges of each intro and credits span. */
internal fun cutEdges(chapters: List<Chapter>, spans: List<Marker>): List<Long> =
	chapters.map { it.start.toLong() } + spans.flatMap { listOf(it.start.toLong(), it.end.toLong()) }

/** The intro and credits spans as stretches of the bar, in milliseconds. */
internal fun markRanges(spans: List<Marker>): List<LongRange> = spans.map { it.start.toLong()..it.end.toLong() }

/**
 * The player's screen: the picture, and over it the controls, which a tap shows and which hide 3 s after the last
 * touch while it plays. They stay up while it is paused, loading or has failed, while the bar is scrubbed and while a
 * menu is open. Closing stops the title and tells the server. [dock] moves the screen between the full screen and the
 * mini bar; the controls, the system bars, the keys and Back belong to the full screen only.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(
	model: PlayerModel,
	dock: DockMotion,
	onClose: () -> Unit,
) {
	val context = LocalContext.current
	val container = context.container
	val full = dock.full
	// A linked phone controls this player while it is open.
	DisposableEffect(model) {
		container.link.host.attach(model)
		onDispose { container.link.host.detach(model) }
	}
	val playing = model.isPlaying
	val engine = model.player
	val cast = container.cast.takeUnless { model.isOffline }
	val receiver = model.castReceiver
	val pendingReceiver = cast?.connecting
	val castStatus = when {
		pendingReceiver != null -> "Connecting to ${pendingReceiver.name}…"
		receiver == null -> null
		model.phase is PlayerPhase.Failed -> "${receiver.id.provider.label} · ${receiver.name}"
		model.phase == PlayerPhase.Starting || model.isBuffering -> "Loading on ${receiver.name}…"
		playing -> "Playing on ${receiver.name}"
		else -> "Paused on ${receiver.name}"
	}
	val pip = rememberPictureInPicture(model)
	if (cast != null) LookForCastDevices(cast, visible = !pip.active && full)
	var choosingCast by rememberSaveable { mutableStateOf(false) }
	val haptics = LocalHapticFeedback.current
	val accent = rememberPlayerAccent()
	val window = LocalActivity.current?.window
	var shown by remember { mutableStateOf(true) }
	var touches by remember { mutableIntStateOf(0) }
	var offerShown by remember { mutableStateOf(true) }
	// The side panel: the trail of pages opened, or empty while it is closed. Closes when left alone, see PANEL_DWELL_MS.
	var pages by remember { mutableStateOf(emptyList<TracksPage>()) }
	var panelTouched by remember { mutableLongStateOf(0L) }
	// A picture strength is being dragged in the panel: the panel and the controls step aside, leaving that slider in its
	// capsule and the picture split into before and after.
	var tuning by remember { mutableStateOf(false) }
	var locked by rememberSaveable { mutableStateOf(false) }
	var lockStamp by remember { mutableIntStateOf(0) }
	var unlockStamp by remember { mutableIntStateOf(0) }
	var aspect by rememberSaveable { mutableStateOf(PlayerAspect.Fit) }
	var aspectStamp by remember { mutableIntStateOf(0) }
	var showsRemaining by rememberSaveable { mutableStateOf(true) }
	var muted by rememberSaveable { mutableStateOf(false) }
	var held by remember { mutableStateOf(false) }
	var heldSpeed by remember { mutableFloatStateOf(1f) }
	var rootFocused by remember { mutableStateOf(false) }
	var positionMs by remember { mutableLongStateOf(model.movieMs()) }
	var bufferedMs by remember { mutableLongStateOf(0L) }
	val sleep = rememberSaveable(saver = SleepTimer.Saver) { SleepTimer() }
	val profile by remember(container) { container.session.state.map { it.user?.uuid }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
	val look by remember(container, profile) { container.settings.subtitleLook(profile) }.collectAsStateWithLifecycle(SubtitleLook())
	LaunchedEffect(model, look.reveal) { model.tracks.setReveal(look.reveal) }
	val sample = remember { listOf(Cue.Builder().setText("Subtitles look like this").build()) }
	val focus = remember { FocusRequester() }
	val lastPages = remember { arrayOf(listOf(TracksPage.Subtitles)) }
	if (pages.isNotEmpty()) lastPages[0] = pages
	val panelOpen = pages.isNotEmpty()
	// A narrow screen has no room beside the picture for the episodes: they come up from the bottom instead.
	val wide = LocalConfiguration.current.screenWidthDp.dp >= WIDE
	val episodesSheet = !wide && pages.lastOrNull() == TracksPage.Episodes
	val menuOpen = panelOpen || choosingCast

	val hold = remember { PositionHold() }
	val scrub = remember(model, hold) {
		ScrubState(
			began = { model.scrubbing = true },
			moved = { seconds -> model.previews?.want(seconds) },
			ended = { seconds ->
				model.scrubbing = false
				model.previews?.settle()
				hold.hold((seconds * 1000).toLong(), SystemClock.elapsedRealtime())
				if (!model.seekTo(seconds)) hold.release()
			},
		)
	}
	val brightness = remember(window) { Brightness(window, context.contentResolver) }
	val volume = remember(context) { Volume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager) }
	val brightnessMeter = remember { Meter() }
	val volumeMeter = remember { Meter() }
	val swipeFrom = remember { floatArrayOf(0f) }

	fun changeAspect(next: PlayerAspect) {
		if (next == aspect) return
		aspect = next
		aspectStamp++
	}

	fun unlock() {
		locked = false
		shown = true
		touches++
	}

	// Back closes a page, then the panel, then the lock; with none of them open it is not taken.
	fun back(): Boolean = when {
		choosingCast -> {
			choosingCast = false
			true
		}
		panelOpen -> {
			pages = pages.dropLast(1)
			true
		}
		locked -> {
			unlock()
			true
		}
		else -> false
	}

	fun onKey(event: KeyEvent): Boolean {
		if (event.type != KeyEventType.KeyDown || !full) return false
		if (event.key == Key.Escape) {
			if (!back()) onClose()
			return true
		}
		if (locked || menuOpen) return false
		val action = keyAction(event.key, rootFocused) ?: return false
		if (!model.canControl) return false
		if (event.nativeKeyEvent.repeatCount > 0 && !action.repeats) return true
		touches++
		when (action) {
			KeyAction.Toggle -> {
				shown = true
				model.userActed()
				model.toggle()
			}
			KeyAction.Play -> {
				shown = true
				model.userActed()
				model.setPlaying(true)
			}
			KeyAction.Pause -> {
				shown = true
				model.userActed()
				model.pause()
			}
			KeyAction.Back -> {
				shown = true
				model.skip(-model.skipInterval)
			}
			KeyAction.Forward -> {
				shown = true
				model.skip(model.skipInterval)
			}
			KeyAction.VolumeUp, KeyAction.VolumeDown -> {
				if (receiver?.id?.provider == CastProvider.AirPlay) return false
				if (receiver != null) {
					model.adjustCastVolume(if (action == KeyAction.VolumeUp) 0.05 else -0.05)
				} else {
					muted = false
					volumeMeter.show(volume.step(up = action == KeyAction.VolumeUp))
				}
			}
			KeyAction.Aspect -> {
				if (receiver != null) return false
				changeAspect(if (aspect == PlayerAspect.Fit) PlayerAspect.Fill else PlayerAspect.Fit)
			}
			KeyAction.Mute -> {
				if (receiver?.id?.provider == CastProvider.AirPlay) return false
				if (receiver != null) model.toggleCastMute() else {
					muted = !muted
					volumeMeter.show(if (muted) 0f else volume.level())
				}
			}
			KeyAction.Next -> {
				shown = true
				model.userActed()
				model.playNext()
			}
		}
		return true
	}

	// The picture's gestures. Only stable things are captured, so one instance serves for as long as the model does.
	val picture = remember(model, scrub, dock) {
		PictureActions(
			locked = { locked },
			canHold = { model.player != null && model.isPlaying && !scrub.active },
			tap = { x, width -> model.tap(x, width) },
			lockedTap = { unlockStamp++ },
			hold = { on ->
				if (on) {
					heldSpeed = model.speed
					model.setSpeed(HOLD_SPEED)
					held = true
					haptics.performHapticFeedback(HapticFeedbackType.LongPress)
				} else if (held) {
					model.setSpeed(heldSpeed)
					held = false
				}
			},
			swipeBegan = { kind -> swipeFrom[0] = if (kind == SwipeKind.Brightness) brightness.level() else volume.level() },
			swiped = { kind, dy, height ->
				val level = swipeLevel(swipeFrom[0], dy, height)
				if (kind == SwipeKind.Brightness) {
					brightness.set(level)
					brightnessMeter.show(brightness.level())
				} else {
					muted = false
					volume.set(level)
					volumeMeter.show(volume.level())
				}
			},
			minimiseBegan = dock::dragBegan,
			minimised = dock::dragTo,
			minimiseEnded = dock::dragEnded,
			scrubBegan = { if (model.durationMs > 0L) scrub.begin(model.movieMs() / 1000.0) },
			scrubbed = { dx, width ->
				val duration = model.durationMs / 1000.0
				scrub.moveTo(scrubbed(scrub.seconds, dx, width, pictureSpan(duration), duration))
			},
			scrubEnded = { scrub.finish() },
			pinched = { zoom -> pinchAspect(zoom)?.let { changeAspect(it) } },
		)
	}

	// Holds the page's trailer and theme tune quiet for as long as the player is open, and the app's own sound while it plays.
	DisposableEffect(container) {
		val open = container.playback.holdPlayerOpen()
		onDispose { open.release() }
	}
	DisposableEffect(container, playing) {
		val audio = if (playing) container.playback.holdAudio() else null
		onDispose { audio?.release() }
	}
	// The window's brightness is the player's only while it is full screen; the bar and closing give it back. The
	// double-speed hold is the player's only while it is open.
	DisposableEffect(brightness) { onDispose { brightness.restore() } }
	LaunchedEffect(brightness, full) { if (full) brightness.reclaim() else brightness.release() }
	DisposableEffect(model) { onDispose { if (held) model.setSpeed(heldSpeed) } }
	BackgroundPlaybackEffect(model, pip)
	// Enhance Picture runs only while this screen draws the title full size; the bar, picture-in-picture and a glasses display do not.
	val drawsHere = !container.glasses.drawsPicture
	LaunchedEffect(model, full, pip.active, drawsHere) { model.enhanceVisible(full && !pip.active && drawsHere) }
	DisposableEffect(model) { onDispose { model.enhanceVisible(false) } }
	// While a display shows Marquee's own screen, it draws the picture and the remote drives this player.
	DisposableEffect(model, container) {
		container.glasses.attachPlayer(model, onClose)
		onDispose { container.glasses.detachPlayer(model) }
	}
	LaunchedEffect(model.finished) { if (model.finished) onClose() }
	LaunchedEffect(model) {
		model.controlTaps.collect { toggle -> if (!locked) shown = if (toggle) !shown else true }
	}
	LaunchedEffect(cast?.enabled) { if (cast?.enabled != true) choosingCast = false }
	LaunchedEffect(cast?.connectionProblem) {
		if (!choosingCast) cast?.connectionProblem?.let { container.toasts.show(it) }
	}
	LaunchedEffect(menuOpen, full) {
		model.menusOpen = menuOpen
		if (!menuOpen && full) focus.requestFocus()
	}
	// Minimising puts the controls, the side panel and the cast list away; expanding shows the controls for a moment.
	LaunchedEffect(full) {
		shown = full
		if (!full) {
			pages = emptyList()
			choosingCast = false
		}
	}
	LaunchedEffect(engine, muted) { engine?.volume = if (muted) 0f else 1f }
	LaunchedEffect(model.skipping) { if (model.skipping != null) hold.release() }
	// The timer pauses the title when it runs out, on the device's clock, which runs with the screen off.
	LaunchedEffect(sleep.deadline) {
		if (!sleep.set) return@LaunchedEffect
		delay(sleep.remainingMs())
		model.pause()
		sleep.cancel()
	}
	// An offer shows when it arises, hides after its dwell (or its countdown, if longer) and returns with the controls.
	val countdownMs = model.offers.countdown?.lengthMs ?: 0L
	LaunchedEffect(model.offers.offer, shown, touches, countdownMs) {
		offerShown = true
		if (model.offers.offer != null && !shown) {
			delay(maxOf(OFFER_DWELL_MS, countdownMs + 1_000L))
			offerShown = false
		}
	}
	// The controls stay up while the picture is still, a menu is open or the bar is being scrubbed; else they hide.
	val still = !playing || model.isBuffering
	LaunchedEffect(shown, still, touches, menuOpen, scrub.active) {
		if (still || menuOpen || scrub.active) {
			shown = true
		} else if (shown) {
			delay(CONTROLS_DWELL_MS)
			shown = false
		}
	}
	LaunchedEffect(panelOpen) {
		if (!panelOpen) return@LaunchedEffect
		panelTouched = SystemClock.elapsedRealtime()
		while (true) {
			if (tuning) panelTouched = SystemClock.elapsedRealtime()
			val left = PANEL_DWELL_MS - (SystemClock.elapsedRealtime() - panelTouched)
			if (left <= 0) break
			delay(left)
		}
		pages = emptyList()
	}
	val chromeOn = (shown || scrub.active) && !locked && !pip.active && full && !tuning
	// The bar's position is read from the player only while the bar is on screen.
	LaunchedEffect(model, chromeOn) {
		if (!chromeOn) return@LaunchedEffect
		while (true) {
			if (!scrub.active) {
				positionMs = hold.shown(model.movieMs(), SystemClock.elapsedRealtime())
				val player = model.player
				bufferedMs = if (player == null) positionMs else positionMs + (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
			}
			delay(POSITION_POLL_MS)
		}
	}
	// From the full screen Back works the player: a menu, then the lock, then it closes. From the bar it is the page's.
	if (full) {
		BackHandler { if (!back()) onClose() }
		HideSystemBars()
	}

	// The picture is a layer of its own, framed by the dock: a thumbnail's width in the bar, the whole window at the
	// full screen. Everything over it is laid out at the window's size and cut by the frame, so the controls never
	// squeeze. A video's letterbox is black on every theme (the frame paints it): the theme's canvas would frame it in
	// light on a light one.
	if (engine != null && !container.glasses.drawsPicture) Video(engine, aspect, keepScreenOn = playing && full, pip = pip, dock = dock)
	Box(
		dock.overlayFrame(Modifier)
			.focusRequester(focus)
			.onFocusChanged { rootFocused = it.isFocused }
			.focusable(enabled = full)
			.onKeyEvent { onKey(it) },
	) {
		if (engine != null) {
			// The subtitle lines are the app's own, over the picture and under the controls and the tap that brings them up.
			CaptionView(
				cues = if (pages.lastOrNull() == TracksPage.Style && model.tracks.cues.isEmpty()) sample else model.tracks.cues,
				look = look,
				controlsUp = chromeOn,
				modifier = Modifier.fillMaxSize(),
			)
			if (!pip.active && full) {
				Box(Modifier.fillMaxSize().pointerInput(picture) { detectPictureGestures(picture) })
			}
		} else if (receiver != null && !pip.active && full) {
			Box(Modifier.fillMaxSize().pointerInput(model) {
				detectTapGestures { point ->
					if (locked) unlockStamp++ else model.tap(point.x, size.width.toFloat())
				}
			})
			if (!chromeOn && !locked && castStatus != null) {
				Text(
					castStatus, color = PlayerInk.ink2, style = MaterialTheme.typography.bodyLarge,
					modifier = Modifier.align(Alignment.Center).padding(Space.s6), textAlign = TextAlign.Center,
				)
			}
		}
		if (model.canControl && !pip.active) SkipRipple(model.skipping, Modifier.fillMaxSize())
		if (!pip.active) {
			val motion = MaterialTheme.motionScheme
			if (full) when (val phase = model.phase) {
				PlayerPhase.Starting -> LoadingIndicator(Modifier.align(Alignment.Center), color = accent)
				is PlayerPhase.Failed -> Failure(phase.message, accent, onRetry = model::retry, onClose = onClose, modifier = Modifier.align(Alignment.Center))
				PlayerPhase.Playing -> if (model.isBuffering && !chromeOn) LoadingIndicator(Modifier.align(Alignment.Center), color = accent)
			}
			AnimatedVisibility(
				visible = chromeOn,
				enter = fadeIn(motion.defaultEffectsSpec()),
				exit = fadeOut(motion.fastEffectsSpec()),
			) {
				val tracks = model.tracks
				val plan = model.plan
				val duration = model.durationMs
				val spans = plan?.let { it.intro + it.credits }.orEmpty()
				val state = ChromeState(
					title = model.title,
					subtitle = castStatus ?: model.subtitle,
					ready = model.canControl,
					playing = playing,
					buffering = model.isBuffering,
					positionMs = positionMs,
					durationMs = duration,
					bufferedMs = bufferedMs,
					cuts = remember(plan, duration) { seekCuts(duration, cutEdges(plan?.chapters.orEmpty(), spans)) },
					bands = remember(plan, duration) { seekBands(duration, markRanges(spans)) },
					skipSeconds = model.skipInterval,
					showsRemaining = showsRemaining,
					speed = model.speed,
					aspect = aspect,
					hasSubtitles = tracks.lists.menuSubtitles.isNotEmpty() || model.canFindSubtitles,
					subtitlesOn = tracks.subtitleId != null,
					hasAudioChoices = tracks.lists.audio.size > 1,
					qualityLabel = QualityPreset.title(model.quality).takeIf { model.quality != QualityPreset.AUTO },
					canChangeQuality = !model.isOffline,
					canPip = pip.supported && receiver == null,
					hasChapters = plan?.chapters?.isNotEmpty() == true,
					chapters = plan?.chapters.orEmpty(),
					hasEpisodes = !model.isOffline && plan?.item?.let { it.type == MediaType.Episode && it.grandparentRatingKey != null } == true,
					hasNext = model.next != null,
					casting = receiver != null,
				)
				val actions = ChromeActions(
					back = onClose,
					toggle = {
						touches++
						model.userActed()
						model.toggle()
					},
					skip = { seconds ->
						touches++
						model.skip(seconds)
					},
					subtitles = { pages = listOf(TracksPage.Subtitles) },
					audio = { pages = listOf(TracksPage.Audio) },
					quality = { pages = listOf(TracksPage.Quality) },
					speed = { pages = listOf(TracksPage.Speed) },
					pip = pip::enter,
					more = { pages = listOf(TracksPage.More) },
					lock = {
						locked = true
						lockStamp++
					},
					aspect = { changeAspect(aspect.next()) },
					chapters = { pages = listOf(TracksPage.Chapters) },
					episodes = { pages = listOf(TracksPage.Episodes) },
					next = {
						touches++
						model.userActed()
						model.playNext()
					},
					remaining = {
						touches++
						showsRemaining = !showsRemaining
					},
					cast = if (cast?.enabled == true && (model.canControl || receiver != null || pendingReceiver != null)) {
						{ pages = emptyList(); choosingCast = true }
					} else null,
					minimise = dock::minimise,
				)
				PlayerChrome(state, actions, scrub, model.previews)
			}
			OfferOverlay(
				model = model,
				visible = offerShown && !menuOpen && !locked && full,
				modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = Space.s4, bottom = OFFER_LIFT),
			)
			StatsOverlay(model, Modifier.align(Alignment.TopStart))
			FormatBadgeHost(model, chromeOn, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = Space.s4))

			val noteAt = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = NOTE_TOP)
			Note(aspectStamp, aspect.label, R.drawable.ic_aspect_ratio, ASPECT_NOTE_MS, noteAt)
			Note(lockStamp, "Controls Locked", R.drawable.ic_lock, LOCK_NOTE_MS, noteAt)
			HoldPill(held, noteAt)
			if (!locked && !held && !menuOpen) SubtitleSearchBanner(model.subtitleFinder, noteAt)
			MeterView(
				brightnessMeter,
				icon = { R.drawable.ic_brightness },
				accent = accent,
				modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(Space.s6),
			)
			MeterView(
				volumeMeter,
				icon = { level -> if (level <= 0f) R.drawable.ic_volume_off else R.drawable.ic_volume_up },
				accent = accent,
				modifier = Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(Space.s6),
			)

			// The picture shows before and after side by side while a strength is dragged.
			if (tuning) EnhanceSplitOverlay(Modifier.matchParentSize())
			if (panelOpen) {
				// A tap outside the panel closes it. The picture stays in view, so a change of look is seen as it is made.
				Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { pages = emptyList() }) })
			}
			AnimatedVisibility(
				visible = panelOpen && !episodesSheet,
				modifier = Modifier.align(Alignment.CenterEnd),
				enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { it / 3 },
				exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec<IntOffset>()) { it / 3 },
			) {
				TracksPanel(
					model = model,
					look = look,
					onLook = { change -> profile?.let { id -> container.scope.launch { container.settings.updateSubtitleLook(id, change) } } },
					pages = pages.ifEmpty { lastPages[0] },
					aspect = aspect,
					onAspect = { changeAspect(it) },
					sleep = sleep,
					pip = pip,
					dragging = tuning,
					onDragging = { tuning = it },
					onPush = { page -> pages = pages + page },
					onPop = { pages = pages.dropLast(1) },
					onClose = { pages = emptyList() },
					onTouch = { panelTouched = SystemClock.elapsedRealtime() },
				)
			}
			AnimatedVisibility(
				visible = panelOpen && episodesSheet,
				modifier = Modifier.align(Alignment.BottomCenter),
				enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec<IntOffset>()) { it / 3 },
				exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec<IntOffset>()) { it / 3 },
			) {
				EpisodeSheet(model, onClose = { pages = emptyList() }, onTouch = { panelTouched = SystemClock.elapsedRealtime() })
			}
			if (locked) {
				UnlockPill(unlockStamp, onUnlock = { unlock() }, modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(Space.s4))
			}
		}
	}
	if (choosingCast && cast?.enabled == true && !pip.active) {
		CastChooser(
			cast = cast,
			onConnect = model::connectCast,
			onLocal = { model.disconnectCast(); choosingCast = false },
			onClose = { model.cancelCastConnect(); choosingCast = false },
		)
	}
}

@Composable
@OptIn(UnstableApi::class)
private fun Video(engine: ExoPlayer, aspect: PlayerAspect, keepScreenOn: Boolean, pip: PictureInPicture, dock: DockMotion) {
	AndroidView(
		factory = { context ->
			PlayerView(context).apply {
				useController = false
				// CaptionView draws the lines, in the person's own look.
				subtitleView?.visibility = View.GONE
			}
		},
		// The screen stays on while the video plays full screen, and times out when it is paused or minimised.
		update = {
			it.player = engine
			it.resizeMode = aspect.resizeMode
			it.keepScreenOn = keepScreenOn
		},
		onRelease = { it.player = null },
		modifier = dock.pictureFrame(Modifier).reportPicture(pip, dock),
	)
}

@Composable
private fun Failure(message: String, accent: Color, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
	val shape = RoundedCornerShape(Radius.card)
	Column(
		modifier
			.padding(Space.s6)
			.widthIn(max = 420.dp)
			.clip(shape)
			.background(PlayerInk.card)
			.border(1.dp, PlayerInk.cardEdge, shape)
			.padding(Space.s5),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		Text(message, style = MaterialTheme.typography.titleMedium, color = PlayerInk.ink, textAlign = TextAlign.Center)
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
			GlassPill("Try Again", onRetry, fill = accent, content = PlayerInk.onAccent)
			GlassPill("Close", onClose)
		}
	}
}

/**
 * The way out of the lock, up for a moment after a touch on the locked screen. Panel care: a 48 dp glass pill at the
 * bottom-start, still, up for 2.5 s and then faded out; nothing else is drawn while the screen is locked.
 */
@Composable
private fun UnlockPill(stamp: Int, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
	var visible by remember { mutableStateOf(false) }
	LaunchedEffect(stamp) {
		if (stamp == 0) return@LaunchedEffect
		visible = true
		delay(UNLOCK_DWELL_MS)
		visible = false
	}
	AnimatedVisibility(
		visible,
		modifier,
		enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
		exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()),
	) {
		GlassPill("Unlock", onUnlock, icon = R.drawable.ic_lock_open)
	}
}

/** [ms] as `m:ss`, or `h:mm:ss` from an hour up. */
internal fun clock(ms: Long): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val hours = total / 3600
	val minutes = total % 3600 / 60
	val seconds = total % 60
	return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

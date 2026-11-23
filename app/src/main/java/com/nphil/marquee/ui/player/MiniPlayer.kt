package com.nphil.marquee.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.LocalPlaybackHttp
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.PlayerPhase
import com.nphil.marquee.ui.components.ResizeHandle
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The tag of the player's frame for tests: it is there for as long as a title or channel is open. */
internal const val DOCK_TAG = "player-dock"

/** The resize handle's grip, centred on the bar's top edge: wide enough to hold while its pill grows, and short so the bar's own swipes keep the rest. */
private val HandleWidth = 96.dp
private val HandleHeight = Space.s5

/** The bar's progress line. It is also the edge that parts the bar from the page above. */
private val MiniLine = 2.dp

/** How often the bar reads where the title is, for its line. */
private const val PROGRESS_POLL_MS = 1_000L

/** How long a phone is given to stand upright once the player has let go of a landscape lock. */
private const val ORIENTATION_WAIT_MS = 1_500L

/** What the bar shows and does for what is playing, whether a title or a live channel. */
@Stable
internal interface DockedMedia {
	val title: String
	val subtitle: String?
	val playing: Boolean

	/** Opening, or waiting for the stream to carry on. */
	val busy: Boolean

	/** The picture is on another screen, so the bar's thumbnail has nothing to show. */
	val casting: Boolean

	/** How far through, 0 to 1, or null when that is not known. Read about once a second while the bar is up. */
	fun progress(): Float?

	fun toggle()

	/** Stops what plays, at once. The model itself is released when the player's screen is gone. */
	fun stop()
}

private class TitleMedia(private val model: PlayerModel) : DockedMedia {
	override val title get() = model.title
	override val subtitle get() = (model.phase as? PlayerPhase.Failed)?.message ?: model.subtitle
	override val playing get() = model.isPlaying
	override val busy get() = model.phase == PlayerPhase.Starting || model.isBuffering
	override val casting get() = model.player == null && model.castReceiver != null

	override fun progress(): Float? = model.durationMs.takeIf { it > 0L }
		?.let { (model.movieMs().toFloat() / it).coerceIn(0f, 1f) }

	override fun toggle() {
		model.toggle()
	}

	override fun stop() {
		model.pause()
	}
}

/**
 * The app's player, above the pages. Each session is one title or channel: it keeps its own composition, so the one
 * ExoPlayer and the one picture serve the full screen and the bar, and minimising and expanding never reload. [live]
 * draws a channel's session, which belongs to the Live TV package. The layer slides off the screen while [hidden], as a
 * trailer plays over the pages; [hosted] says the pages on the stack are the tabs, which the bar then sits under.
 */
@Composable
internal fun PlayerDockLayer(dock: PlayerDock, hidden: Boolean, hosted: Boolean, live: @Composable (DockSession, PlayRequest.Channel) -> Unit) {
	val motion = MaterialTheme.motionScheme
	AnimatedContent(
		targetState = dock.session,
		// The whole window, so the size animation has nothing to do: the frame inside moves itself.
		modifier = Modifier.fillMaxSize().awayWhen(hidden),
		transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
		label = "player dock",
		contentKey = { it?.id },
	) { session ->
		if (session != null) {
			DockedSession(dock, session) { request ->
				when (request) {
					is PlayRequest.Title -> TitleSession(dock, session, request, hosted)
					is PlayRequest.Channel -> live(session, request)
				}
			}
		}
	}
}

/** Places the layer below the window while [away]: it keeps playing, unseen and out of reach. */
private fun Modifier.awayWhen(away: Boolean): Modifier = if (!away) this else layout { measurable, constraints ->
	val placeable = measurable.measure(constraints)
	layout(placeable.width, placeable.height) { placeable.place(0, placeable.height) }
}

/** What each session lives in: its own store of view models, so its player stops when its screen goes. */
@Composable
private fun DockedSession(dock: PlayerDock, session: DockSession, content: @Composable (PlayRequest) -> Unit) {
	val activity = LocalActivity.current
	val owner = remember(session.id) { dock.owner(session.id) }
	// A turn or a resize that makes a new activity keeps the player, as it keeps a page's; only the session's end releases it.
	DisposableEffect(session.id) { onDispose { if (activity?.isChangingConfigurations != true) dock.release(session.id) } }
	CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content(session.request) }
}

@Composable
private fun TitleSession(dock: PlayerDock, session: DockSession, request: PlayRequest.Title, hosted: Boolean) {
	val context = LocalContext.current
	val container = context.container
	val http = LocalPlaybackHttp.current ?: container.http
	val model = viewModel {
		PlayerModel(
			container, http, context, request.ratingKey, request.titleKey ?: request.ratingKey, request.fromStart,
			downloadId = request.downloadId, recordingMediaID = request.recordingMediaID,
		)
	}
	val media = remember(model) { TitleMedia(model) }
	DockFrame(dock, session.id, media, hosted) { motion -> PlayerScreen(model, motion) { motion.close() } }
}

/**
 * Where the player is, between the full screen and the bar, and the gestures that move it. One [level] says it all:
 * 1 is the full screen, 0 the bar. A finger sets it directly, and a spring takes it the rest of the way, so the frame,
 * the picture and the controls all follow one number. It is read in layout and draw only, so a drag recomposes nothing.
 */
@Stable
internal class DockMotion(
	private val dock: PlayerDock,
	private val id: Long,
	private val media: DockedMedia,
	private val scope: CoroutineScope,
	private val inPip: State<Boolean>,
) {
	private var level by mutableFloatStateOf(if (dock.state == DockState.Bar) 0f else 1f)

	/** The rest the level is at, or on its way to. */
	private var heading = if (dock.state == DockState.Bar) DockRest.Bar else DockRest.Full
	private var sway by mutableFloatStateOf(0f)
	private var levelRunning by mutableStateOf(false)
	private var swayRunning by mutableStateOf(false)
	private var dragStart = 1f
	private var levelJob: Job? = null
	private var swayJob: Job? = null
	private var levelRun = 0
	private var swayRun = 0

	/** Where the bar is in the window, in pixels. The layer sets it; it is read in layout, draw and gestures. */
	var barRect: () -> Rect = { Rect.Zero }

	/** The window, in pixels; the frame's layout sets it before it measures what is inside. */
	var window = IntSize.Zero

	var density = 1f
	var spatial: FiniteAnimationSpec<Float> = spring()

	/** The bar's height in pixels as the person has it. The layer sets it; it is read in layout and draw only. */
	var barHeight: () -> Float = { 0f }

	/** The picture's width in the bar, which follows the bar's height. */
	val thumbWidth: Float get() = miniThumbWidth(barHeight())

	/** The player is, or is on its way to, the full screen: its controls and the system bars belong to it. */
	val full: Boolean get() = dock.state != DockState.Bar

	/** The frame is in motion, by a finger or a spring. The picture's place is not reported to the system meanwhile. */
	val moving: Boolean get() = levelRunning || swayRunning

	/** How far from the bar the player shows: 1 in picture in picture, where the window is the player. */
	val shown: Float get() = if (inPip.value) 1f else level

	/** The frame, in the window: the bar's rectangle grown to the whole window, then slid by a swipe sideways. */
	fun frame(): IntRect {
		val rect = lerp(barRect(), Rect(0f, 0f, window.width.toFloat(), window.height.toFloat()), shown)
		val slide = if (inPip.value) 0 else sway.roundToInt()
		return IntRect(rect.left.roundToInt() + slide, rect.top.roundToInt(), rect.right.roundToInt() + slide, rect.bottom.roundToInt())
	}

	/**
	 * The picture's frame: as tall as the player and as wide as the bar's thumbnail in the bar, the whole width at the
	 * full screen. The video is measured at that size, so it is never scaled from one size to another.
	 */
	fun pictureFrame(modifier: Modifier): Modifier = modifier.layout { measurable, constraints ->
		val width = lerp(thumbWidth, constraints.maxWidth.toFloat(), shown).roundToInt().coerceIn(0, constraints.maxWidth)
		val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
		layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
	}

	/**
	 * The controls' frame: always the window's size, laid out as at the full screen, and cut short by the frame while it
	 * is smaller. They fade in over the last of the way up and out over the first of the way down, so they never show
	 * squeezed.
	 */
	fun overlayFrame(modifier: Modifier): Modifier = modifier
		.layout { measurable, constraints ->
			val placeable = measurable.measure(Constraints.fixed(window.width, window.height))
			layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
		}
		.graphicsLayer { alpha = overlayAlpha(shown) }

	fun minimise() {
		dock.minimise()
		settle(0f, 0f)
	}

	fun expand() {
		dock.expand()
		settle(1f, 0f)
	}

	/**
	 * The dock's state changed. The buttons and gestures move the frame themselves; a change that is not theirs (the
	 * title opened again from its page, a display asking for Settings) moves it here.
	 */
	fun follow(state: DockState) {
		val rest = state.rest() ?: return
		if (rest != heading) settle(if (rest == DockRest.Bar) 0f else 1f, 0f)
	}

	/** Stops what plays and takes the player away. */
	fun close() {
		media.stop()
		dock.close(id)
	}

	/** A drag of the picture or the bar begins: the finger takes the frame wherever it is. */
	fun dragBegan() {
		levelJob?.cancel()
		levelJob = null
		levelRun++
		dragStart = level
		levelRunning = true
	}

	/** The finger is [dy] pixels from where it began (down is positive). */
	fun dragTo(dy: Float) {
		level = dockExpansion(dragStart, dy, travel())
	}

	/** The finger lifted at [velocityY] pixels per second (down is positive). */
	fun dragEnded(velocityY: Float) {
		val from = if (dragStart >= 0.5f) DockRest.Full else DockRest.Bar
		val rest = dockRest(from, level, velocityY / density)
		if (rest == DockRest.Bar) dock.minimise() else dock.expand()
		// A flick on the way carries its speed into the spring, so the frame does not stop and start again.
		settle(if (rest == DockRest.Bar) 0f else 1f, -velocityY / travel())
	}

	/** A swipe along the bar begins. */
	fun swayBegan() {
		swayJob?.cancel()
		swayJob = null
		swayRun++
		swayRunning = true
	}

	fun swayTo(dx: Float) {
		sway = dx
	}

	/** The finger lifted at [velocityX] pixels per second: the bar leaves, stopping the player, or comes back. */
	fun swayEnded(velocityX: Float) {
		val side = dockDismiss(sway, velocityX / density, barRect().width)
		if (side == 0) {
			glide(0f, velocityX) {}
		} else {
			media.stop()
			glide(side * window.width.toFloat(), velocityX) { dock.close(id) }
		}
	}

	private fun travel(): Float = barRect().top.takeIf { it > 0f } ?: window.height.toFloat()

	private fun settle(target: Float, velocity: Float) {
		heading = if (target == 0f) DockRest.Bar else DockRest.Full
		levelJob?.cancel()
		val run = ++levelRun
		levelRunning = true
		levelJob = scope.launch {
			try {
				animate(level, target, velocity, spatial) { value, _ -> level = value }
			} finally {
				if (run == levelRun) levelRunning = false
			}
		}
	}

	private fun glide(target: Float, velocity: Float, then: () -> Unit) {
		swayJob?.cancel()
		val run = ++swayRun
		swayRunning = true
		swayJob = scope.launch {
			try {
				animate(sway, target, velocity, spatial) { value, _ -> sway = value }
				then()
			} finally {
				if (run == swayRun) swayRunning = false
			}
		}
	}
}

/**
 * Tells [pip] where the picture is, for the system's animation into the small window. The place is reported once the
 * frame has stopped moving, not on each frame of a move: every report is a call to the system.
 */
@Composable
internal fun Modifier.reportPicture(pip: PictureInPicture, dock: DockMotion): Modifier {
	val place = remember { arrayOf(Rect.Zero) }
	LaunchedEffect(dock.moving) { if (!dock.moving && !place[0].isEmpty) pip.videoBounds(place[0]) }
	return onGloballyPositioned {
		place[0] = it.boundsInWindow()
		if (!dock.moving) pip.videoBounds(place[0])
	}
}

/**
 * The player's frame: the picture and the controls the screen draws in [content], and over them the bar while the
 * frame is short of the full screen. It sits in the window like a page, but is laid out as a rectangle that [DockMotion]
 * moves between the bar's place and the whole window, clipped to it.
 *
 * Panel care: the bar is 56 dp tall until the person drags it taller (at most 160 dp) and as wide as the page it sits
 * under, on the theme's sheet colour, with a 2 dp accent line along its top and, centred under that, a 4 dp by 40 dp
 * handle: ink3 at half strength at rest, the accent only while held or hovered, and never moving on its own. Its
 * thumbnail is the moving picture; its text and buttons stay put while the title plays and the bar rides with the tab
 * bar when that slides away on scroll. A swipe along it sends it off.
 */
@Composable
internal fun DockFrame(dock: PlayerDock, id: Long, media: DockedMedia, hosted: Boolean, content: @Composable (DockMotion) -> Unit) {
	val scope = rememberCoroutineScope()
	val inPip = rememberPictureInPictureMode()
	val motion = remember(dock, id, media) { DockMotion(dock, id, media, scope, inPip) }
	val density = LocalDensity.current
	val direction = LocalLayoutDirection.current
	val anchor = LocalDockAnchor.current
	val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
	val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
	val navigation = WindowInsets.navigationBars
	val sheet = lucent.sheet
	val barSize = LocalMiniBarSize.current
	motion.density = density.density
	motion.spatial = spatial
	motion.barHeight = { barSize.height * motion.density }

	// The bar sits under the tabs' pages while they are the page on the stack, above the system bars otherwise. When
	// that changes it glides from where it was instead of jumping.
	val tabsHosted = rememberUpdatedState(hosted)
	val underTabs by remember(anchor) { derivedStateOf { tabsHosted.value && anchor.pages != null } }
	val last = remember { arrayOfNulls<Rect>(1) }
	val from = remember(underTabs) { last[0] }
	val glide = remember(underTabs) { Animatable(0f) }
	LaunchedEffect(glide) { glide.animateTo(1f, spatial) }
	motion.barRect = {
		val height = motion.barHeight()
		val pages = anchor.pages
		val target = if (underTabs && pages != null) {
			val bottom = pages.bottom + anchor.hidden()
			Rect(pages.left, bottom - height, pages.right, bottom)
		} else {
			val bottom = motion.window.height - navigation.getBottom(density).toFloat()
			Rect(sides.getLeft(density, direction).toFloat(), bottom - height, motion.window.width - sides.getRight(density, direction).toFloat(), bottom)
		}
		val rect = from?.let { lerp(it, target, glide.value) } ?: target
		last[0] = rect
		rect
	}

	TurnUprightWhenMinimised(motion)
	LaunchedEffect(motion) { snapshotFlow { dock.state }.collect { motion.follow(it) } }
	val current = dock.session?.id == id
	// A player that was replaced, or closed some other way, stops at once rather than after its screen has faded.
	LaunchedEffect(current) { if (!current) media.stop() }

	val showsBar by remember(motion) { derivedStateOf { motion.shown < 1f } }
	Box(
		Modifier
			.dockFrame(motion)
			.testTag(DOCK_TAG)
			.clipToBounds()
			.drawBehind {
				val e = motion.shown
				drawRect(lerp(sheet, Color.Black, e))
				drawRect(Color.Black, size = Size(lerp(motion.thumbWidth, size.width, e).coerceAtMost(size.width), size.height))
			},
	) {
		content(motion)
		if (showsBar) MiniBar(media, motion)
	}
}

/** Lays the frame out at its rectangle in the window; what is inside is measured at that size and clipped to it. */
private fun Modifier.dockFrame(motion: DockMotion): Modifier = layout { measurable, constraints ->
	motion.window = IntSize(constraints.maxWidth, constraints.maxHeight)
	val frame = motion.frame()
	val placeable = measurable.measure(Constraints.fixed(frame.width.coerceAtLeast(0), frame.height.coerceAtLeast(0)))
	layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(frame.left, frame.top) }
}

/**
 * A phone that the player held on its side turns upright as the player goes to the bar, since it was the player that
 * turned it. Free rotation comes back once it stands, or after a short wait on a phone that cannot turn.
 */
@Composable
private fun TurnUprightWhenMinimised(motion: DockMotion) {
	val activity = LocalActivity.current
	val orientation by rememberUpdatedState(LocalConfiguration.current.orientation)
	LaunchedEffect(motion.full) {
		if (motion.full || activity == null) return@LaunchedEffect
		val smallest = activity.resources.configuration.smallestScreenWidthDp
		val turn = orientationOnMinimise(activity.requestedOrientation, isPhone(smallest)) ?: return@LaunchedEffect
		activity.requestedOrientation = turn
		withTimeoutOrNull(ORIENTATION_WAIT_MS) { snapshotFlow { orientation }.first { it == Configuration.ORIENTATION_PORTRAIT } }
		activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
	}
}

/**
 * The bar: a thumbnail's room (the picture itself shows through), the title with the episode or programme under it,
 * play and pause, and close. A tap or a swipe up expands it; a swipe along it stops the player. The handle on its top
 * edge drags it taller or shorter, and the picture with it.
 */
@Composable
private fun MiniBar(media: DockedMedia, motion: DockMotion) {
	val theme = lucent
	val barSize = LocalMiniBarSize.current
	val progress = remember { mutableStateOf<Float?>(null) }
	LaunchedEffect(media) {
		while (true) {
			progress.value = media.progress()
			delay(PROGRESS_POLL_MS)
		}
	}
	// While the player expands the bar is still fading out: it takes no touch, and lets none through to its buttons.
	val touch = if (motion.full) Modifier.pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
		}
	} else Modifier.pointerInput(motion) { detectBarGestures(motion) }
	Box(
		Modifier
			.pinnedToBar(motion)
			.graphicsLayer { alpha = barAlpha(motion.shown) }
			.then(touch)
			.semantics {
				contentDescription = "Now playing, ${media.title}"
				onClick("Expand player") { motion.expand(); true }
				customActions = listOf(CustomAccessibilityAction("Close player") { motion.close(); true })
			},
	) {
		Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.pictureRoom(motion), contentAlignment = Alignment.Center) {
				if (media.casting) Icon(painterResource(R.drawable.ic_cast_connected), null, tint = PlayerInk.ink2)
			}
			Column(Modifier.weight(1f).padding(horizontal = Space.s3), verticalArrangement = Arrangement.Center) {
				Text(media.title, style = MaterialTheme.typography.titleSmall, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
				media.subtitle?.let {
					Text(it, style = MaterialTheme.typography.bodySmall, color = theme.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
			IconButton(onClick = media::toggle) {
				if (media.busy) {
					LoadingIndicator(Modifier.size(24.dp), color = theme.accent)
				} else {
					val playing = media.playing
					Icon(
						painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
						if (playing) "Pause" else "Play", tint = theme.ink,
					)
				}
			}
			IconButton(onClick = motion::close) { Icon(painterResource(R.drawable.ic_close), "Close player", tint = theme.ink) }
		}
		Box(
			Modifier.align(Alignment.TopStart).fillMaxWidth().height(MiniLine).drawBehind {
				drawRect(theme.trackOff)
				progress.value?.let { drawRect(theme.accent, size = Size(size.width * it, size.height)) }
			},
		)
		ResizeHandle(
			orientation = Orientation.Vertical,
			label = "Resize player bar",
			value = { barSize.height },
			range = { barSize.range },
			onAdjust = { dp ->
				barSize.adjustTo(dp)
				Diag.info(DiagCategory.Ui, "miniBar.resize", "dp" to barSize.height.roundToInt())
			},
			onStart = barSize::dragStarted,
			onDrag = { travelled -> barSize.dragBy(-travelled / motion.density) },
			onEnd = {
				barSize.dragEnded()
				Diag.info(DiagCategory.Ui, "miniBar.resize", "dp" to barSize.height.roundToInt())
			},
			modifier = Modifier.align(Alignment.TopCenter).size(HandleWidth, HandleHeight),
			atEnd = false,
		)
	}
}

/** The bar's content keeps the bar's size while the frame around it grows, so it does not stretch on the way up. */
private fun Modifier.pinnedToBar(motion: DockMotion): Modifier = layout { measurable, constraints ->
	val bar = motion.barRect()
	val width = bar.width.roundToInt().coerceIn(0, constraints.maxWidth)
	val height = bar.height.roundToInt().coerceIn(0, constraints.maxHeight)
	val placeable = measurable.measure(Constraints.fixed(width, height))
	layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
}

/** The picture's room at the bar's start, which grows with the bar's height. */
private fun Modifier.pictureRoom(motion: DockMotion): Modifier = layout { measurable, constraints ->
	val width = motion.thumbWidth.roundToInt().coerceIn(0, constraints.maxWidth)
	val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
	layout(width, constraints.maxHeight) { placeable.place(0, 0) }
}

/**
 * The bar's own gestures: a tap expands it, a drag up or down moves the frame with the finger, a drag along it slides
 * the bar away. It skips a touch a button has taken, so the buttons keep their taps.
 */
private suspend fun PointerInputScope.detectBarGestures(motion: DockMotion) {
	val slop = viewConfiguration.touchSlop
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = true)
		val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, Offset.Zero) }
		var travelled = Offset.Zero
		var axis: DockAxis? = null
		var lifted = false
		while (true) {
			val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
			if (change.isConsumed) break
			travelled += change.positionChange()
			tracker.addPosition(change.uptimeMillis, travelled)
			if (!change.pressed) {
				lifted = true
				break
			}
			if (axis == null) {
				axis = dockAxis(travelled.x, travelled.y, slop)
				when (axis) {
					DockAxis.Vertical -> motion.dragBegan()
					DockAxis.Horizontal -> motion.swayBegan()
					null -> Unit
				}
			}
			when (axis) {
				DockAxis.Vertical -> {
					motion.dragTo(travelled.y)
					change.consume()
				}
				DockAxis.Horizontal -> {
					motion.swayTo(travelled.x)
					change.consume()
				}
				null -> Unit
			}
		}
		when (axis) {
			DockAxis.Vertical -> motion.dragEnded(tracker.calculateVelocity().y)
			DockAxis.Horizontal -> motion.swayEnded(tracker.calculateVelocity().x)
			null -> if (lifted) motion.expand()
		}
	}
}

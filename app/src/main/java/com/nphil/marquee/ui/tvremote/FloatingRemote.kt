package com.nphil.marquee.ui.tvremote

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.TvRemote
import com.nphil.marquee.ui.shell.LocalShellMetrics
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.Size as TokenSize
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lucent's container transform: the orb and the panel are one surface, so one spring moves both. */
private val ContainerSpring = spring<Float>(dampingRatio = 1f, stiffness = 584f)
private val GlideSpring = spring(dampingRatio = 1f, stiffness = 584f, visibilityThreshold = Offset.VisibilityThreshold)

/** Where the panel opens until the user has moved or resized it. */
private const val DefaultWidth = 380f
private const val DefaultHeight = 660f

/** How far into the open the orb's glyph has gone and the panel's content has come. */
private const val GlyphGone = 0.35f

private class RenderedOrb {
	var origin: Offset? = null
}

/**
 * The TV remote as a button that floats over the app and opens into a floating panel. Shown while [visible]; the
 * button itself only exists while the remote is enabled in Settings.
 */
@Composable
internal fun FloatingRemote(remote: TvRemote, visible: Boolean) {
	val state by remote.state.collectAsStateWithLifecycle()
	val active = visible && state.enabled
	LaunchedEffect(active) { if (!active) remote.close() }
	if (active) RemoteHost(remote, state)
}

@Composable
private fun RemoteHost(remote: TvRemote, state: TvRemote.State) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val direction = LocalLayoutDirection.current
	val haptics = LocalHapticFeedback.current
	val scope = rememberCoroutineScope()
	val metrics = LocalShellMetrics.current
	val store = remember(context) { RemoteLayoutStore(context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)) }

	val orb = with(density) { TokenSize.target.toPx() }
	val sidebarInset = with(density) { 12.dp.toPx() }
	val gap = with(density) { Space.s2.toPx() }
	val margin = with(density) { Space.s4.toPx() }
	val capture = with(density) { 96.dp.toPx() }
	val grid = with(density) { RemoteMetrics.Grid.dp.toPx() }
	val minPanel = with(density) { RemoteMetrics.MinPanel.dp.toPx() }
	val stackedRoom = with(density) { 56.dp.toPx() }
	// Wide enough for the orb to sit in the avatar's row rather than above it.
	val inlineMin = with(density) { 204.dp.toPx() }

	// The saved choice; the sidebar is only the answer while there is one.
	var wanted by remember { mutableStateOf(store.dock ?: RemoteDock.Sidebar) }
	var modules by remember { mutableStateOf(store.modules) }
	var mode by remember { mutableStateOf(store.mode) }
	var editing by remember { mutableStateOf(false) }
	var shown by remember { mutableStateOf(false) }
	var ready by remember { mutableStateOf(false) }
	var reduced by remember { mutableStateOf(false) }
	var dragging by remember { mutableStateOf(false) }
	var dimmed by remember { mutableStateOf(false) }
	var held by remember { mutableStateOf(false) }
	var touches by remember { mutableIntStateOf(0) }
	// Read only inside layout and draw lambdas, so a drag or a resize does not recompose.
	var drag by remember { mutableStateOf<Offset?>(null) }
	var panelOffset by remember { mutableStateOf(Offset.Zero) }
	var panelSize by remember { mutableStateOf(Size.Zero) }
	val progress = remember { Animatable(0f) }
	val fade = remember { Animatable(1f) }
	val residual = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
	val blend = remember { Animatable(if (Snapshot.withoutReadObservation { metrics.sidebarWidth } >= inlineMin) 1f else 0f) }
	val rendered = remember { RenderedOrb() }

	val sidebarAvailable by remember(metrics) { derivedStateOf { metrics.profileBounds != null && metrics.sidebarWidth > 0f } }
	fun effective(dock: RemoteDock): RemoteDock = if (dock is RemoteDock.Sidebar && !sidebarAvailable) DefaultEdge else dock
	fun wake() {
		dimmed = false
		touches++
	}

	// The shell lays itself out in the first frames; showing the orb after that avoids a flash at the wrong spot.
	LaunchedEffect(Unit) {
		withFrameNanos { }
		withFrameNanos { }
		ready = true
	}
	// An orb resting over content dims after the dwell; any touch, drag, open panel or dock change starts the wait again.
	LaunchedEffect(touches, effective(wanted), state.opened, dragging, held) {
		dimmed = false
		if (!dragging && !held && orbDimmed(effective(wanted), state.opened, DimDwellMs)) {
			delay(DimDwellMs)
			dimmed = true
		}
	}
	LaunchedEffect(metrics, inlineMin) {
		snapshotFlow { metrics.sidebarWidth >= inlineMin }.collect { inline -> blend.animateTo(if (inline) 1f else 0f, ContainerSpring) }
	}
	LaunchedEffect(metrics, stackedRoom) {
		snapshotFlow { if (effective(wanted) is RemoteDock.Sidebar) dockRoom(blend.value, stackedRoom) else 0f }.collect { metrics.dockRoom = it }
	}
	DisposableEffect(metrics) { onDispose { metrics.dockRoom = 0f } }

	BoxWithConstraints(Modifier.fillMaxSize()) {
		val width = constraints.maxWidth.toFloat()
		val height = constraints.maxHeight.toFloat()
		val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
		val gestures = WindowInsets.systemGestures
		val safe = Rect(
			bars.getLeft(density, direction).toFloat(), bars.getTop(density).toFloat(),
			width - bars.getRight(density, direction), height - bars.getBottom(density),
		)
		val bounds = safe.deflate(gap)
		// A phone keeps the bottom bar clear; a tablet's sidebar is handled by the field's start edge.
		val reserve = if (maxWidth < 600.dp) with(density) { 80.dp.toPx() } else 0f
		val edgeLeft = max(safe.left, gestures.getLeft(density, direction).toFloat())
		val edgeRight = min(safe.right, width - gestures.getRight(density, direction))
		val half = Offset(orb / 2, orb / 2)

		/** Where the orb may rest at the edges, clear of the sidebar and of the system's edge gestures. */
		fun field() = Rect(max(edgeLeft, metrics.sidebarWidth) + margin, safe.top + margin, edgeRight - margin, safe.bottom - margin - reserve)

		fun homeOf(dock: RemoteDock): Offset = when (dock) {
			is RemoteDock.Edge -> edgeOrigin(field(), dock, orb)
			RemoteDock.Sidebar -> metrics.profileBounds?.let { sidebarOrigin(metrics.sidebarWidth, it, orb, sidebarInset, gap, blend.value) }
				?: edgeOrigin(field(), DefaultEdge, orb)
		}

		fun orbOrigin(): Offset = drag ?: (homeOf(effective(wanted)) + residual.value)

		fun containerBounds(): Rect {
			val orbRect = Rect(orbOrigin(), Size(orb, orb))
			val p = progress.value
			return if (p <= 0f) orbRect else lerp(orbRect, Rect(panelOffset, panelSize), p)
		}

		fun restoredPanel(): Rect {
			store.panel?.let { return fitPanel(it.toRect(safe), bounds, minPanel) }
			val room = if (sidebarAvailable) bounds.copy(left = max(bounds.left, metrics.sidebarWidth + gap)) else bounds
			return defaultPanel(room, Rect(orbOrigin(), Size(orb, orb)), with(density) { DefaultWidth.dp.toPx() }, with(density) { DefaultHeight.dp.toPx() })
		}

		LaunchedEffect(state.opened) {
			if (state.opened) {
				val rect = restoredPanel()
				panelOffset = rect.topLeft
				panelSize = rect.size
				shown = true
				reduced = animationsOff(context)
				if (reduced) {
					progress.snapTo(1f)
					fade.snapTo(0f)
					fade.animateTo(1f, tween(120, easing = LinearEasing))
				} else {
					progress.animateTo(1f, ContainerSpring)
				}
			} else if (shown) {
				editing = false
				if (reduced) {
					fade.animateTo(0f, tween(120, easing = LinearEasing))
					progress.snapTo(0f)
					fade.snapTo(0f)
					fade.animateTo(1f, tween(120, easing = LinearEasing))
				} else {
					progress.animateTo(0f, ContainerSpring)
				}
				shown = false
			}
		}
		// A new window size or insets (rotation, a fold) refits the open panel from its saved fractions.
		LaunchedEffect(safe) {
			if (shown) {
				val rect = restoredPanel().let { fitPanel(it, bounds, minPanel) }
				panelOffset = rect.topLeft
				panelSize = rect.size
			}
		}
		// The orb lands where the sidebar's row is, so it glides there when a sidebar appears or goes.
		LaunchedEffect(sidebarAvailable) {
			val from = rendered.origin
			if (ready && from != null && drag == null) {
				residual.snapTo(from - homeOf(effective(wanted)))
				residual.animateTo(Offset.Zero, GlideSpring)
			}
		}

		fun dockTo(target: RemoteDock) {
			val from = orbOrigin()
			scope.launch {
				wanted = target
				store.dock = target
				residual.snapTo(from - homeOf(effective(target)))
				residual.animateTo(Offset.Zero, GlideSpring)
			}
		}

		fun edgeDock(side: DockSide): RemoteDock {
			val row = (nearestDock(orbOrigin() + half, field(), orb, null, 0f) as? RemoteDock.Edge)?.row ?: DefaultEdge.row
			return RemoteDock.Edge(side, row)
		}

		fun sidebarSpot(): Offset? = if (sidebarAvailable) homeOf(RemoteDock.Sidebar) + half else null

		fun dropOrb(velocity: Velocity) {
			val origin = drag ?: return
			val target = nearestDock(projectedCenter(origin + half, Offset(velocity.x, velocity.y)), field(), orb, sidebarSpot(), capture)
			scope.launch {
				wanted = target
				store.dock = target
				// The orb stays where the finger left it until its new home takes over, then eases in.
				residual.snapTo(origin - homeOf(effective(target)))
				drag = null
				dragging = false
				haptics.performHapticFeedback(if (target is RemoteDock.Sidebar) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentTick)
				residual.animateTo(Offset.Zero, GlideSpring)
			}
		}

		val motion = PanelMotion(
			move = { delta -> panelOffset = movePanel(Rect(panelOffset, panelSize), delta, bounds).topLeft },
			resize = { handle, delta ->
				val rect = resizePanel(Rect(panelOffset, panelSize), handle, delta, bounds, minPanel)
				panelOffset = rect.topLeft
				panelSize = rect.size
			},
			settle = {
				val rect = fitPanel(snapPanel(Rect(panelOffset, panelSize), grid), bounds, minPanel)
				panelOffset = rect.topLeft
				panelSize = rect.size
				store.panel = rect.toFractions(safe)
			},
		)

		val pressSource = remember { MutableInteractionSource() }
		val pressed by pressSource.collectIsPressedAsState()
		val pressScale = animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
		val lift = animateFloatAsState(if (dragging) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec())
		val dimAlpha = animateFloatAsState(if (dimmed) DimmedAlpha else 1f, MaterialTheme.motionScheme.fastEffectsSpec())
		val idle by remember { derivedStateOf { progress.value < 0.01f } }
		val spots = remember(sidebarAvailable) {
			buildList<RemoteDock> {
				for (side in DockSide.entries) for (row in 0 until DockRows) add(RemoteDock.Edge(side, row))
				if (sidebarAvailable) add(RemoteDock.Sidebar)
			}
		}
		val fill = lucent.sheet
		val orbEdge = lucent.edgeRaised
		val panelEdge = lucent.edge
		val dim = lucent.ink3
		val hot = lucent.accent

		if (dragging) {
			// Faint spots show where the orb can rest, and the one it would take is ringed.
			Box(
				Modifier.fillMaxSize().drawBehind {
					val near = drag?.let { nearestDock(it + half, field(), orb, sidebarSpot(), capture) }
					for (spot in spots) {
						val centre = homeOf(spot) + half
						if (spot == near) drawCircle(hot, orb / 2, centre, style = Stroke(2.dp.toPx()))
						else drawCircle(dim, 3.dp.toPx(), centre, alpha = 0.5f)
					}
				},
			)
		}

		val orbInput = Modifier.testTag("tv-remote-orb")
			.rootDrag(
				enabled = idle && !state.opened,
				onStart = {
					dragging = true
					haptics.performHapticFeedback(HapticFeedbackType.LongPress)
					drag = orbOrigin()
					scope.launch { residual.snapTo(Offset.Zero) }
				},
				onDrag = { delta ->
					val next = (drag ?: orbOrigin()) + delta
					drag = Offset(next.x.coerceIn(safe.left, max(safe.left, safe.right - orb)), next.y.coerceIn(safe.top, max(safe.top, safe.bottom - orb)))
				},
				onEnd = ::dropOrb,
			)
			.clickable(interactionSource = pressSource, indication = null, role = Role.Button, onClickLabel = "Open remote") {
				wake()
				haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
				remote.open()
			}
			.semantics {
				contentDescription = "TV Remote"
				customActions = buildList {
					if (sidebarAvailable) add(CustomAccessibilityAction("Dock in sidebar") { wake(); dockTo(RemoteDock.Sidebar); true })
					add(CustomAccessibilityAction("Move to left edge") { wake(); dockTo(edgeDock(DockSide.Start)); true })
					add(CustomAccessibilityAction("Move to right edge") { wake(); dockTo(edgeDock(DockSide.End)); true })
				}
			}
		// An open panel is a surface of its own: its empty areas take touches rather than pass them to the app below.
		val panelInput = Modifier.testTag("tv-remote-panel").semantics { paneTitle = "TV Remote" }.pointerInput(Unit) {}

		Box(
			Modifier
				.offset { containerBounds().topLeft.round() }
				.layout { measurable, _ ->
					val box = containerBounds()
					rendered.origin = orbOrigin()
					// The content is always laid out at the panel's full size and revealed by the clip.
					val full = Constraints(0, max(panelSize.width, orb).roundToInt(), 0, max(panelSize.height, orb).roundToInt())
					val placeable = measurable.measure(full)
					layout(box.width.roundToInt(), box.height.roundToInt()) { placeable.place(0, 0) }
				}
				.graphicsLayer {
					val p = progress.value
					val scale = if (p <= 0f) pressScale.value * (1f + 0.06f * lift.value) else 1f
					scaleX = scale
					scaleY = scale
					// The dim applies only while the container is still the orb; the panel is never dimmed.
					val orbAlpha = if (p <= 0f) dimAlpha.value else 1f
					alpha = (if (!ready) 0f else if (reduced) fade.value else 1f) * orbAlpha
					shape = RoundedCornerShape(lerp(orb / 2, Radius.sheet.toPx(), p))
					clip = true
					// Docked and idle the orb is flat like the sidebar's own rows; it lifts only when it floats or is held.
					shadowElevation = (lift.value * 8.dp.toPx() + p * 12.dp.toPx())
				}
				.drawBehind {
					val p = progress.value
					val radius = lerp(orb / 2, Radius.sheet.toPx(), p)
					val stroke = 1.dp.toPx()
					drawRoundRect(fill, cornerRadius = CornerRadius(radius))
					drawRoundRect(
						lerp(orbEdge, panelEdge, p), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
						CornerRadius(max(radius - stroke / 2, 0f)), Stroke(stroke),
					)
				}
				.pointerInput(Unit) {
					// Initial pass and never consumed, so a touch that starts a drag or a tap still reaches the orb.
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							held = event.changes.any { it.pressed }
							dimmed = false
							if (event.changes.any { it.changedToDownIgnoreConsumed() || it.changedToUpIgnoreConsumed() }) touches++
						}
					}
				}
				.then(if (idle && !state.opened) orbInput else panelInput),
		) {
			Box(Modifier.size(TokenSize.target).graphicsLayer { alpha = (1f - progress.value / GlyphGone).coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
				Icon(painterResource(R.drawable.ic_tv_remote), null, tint = lucent.accentText)
			}
			if (shown) {
				Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (reduced) 1f else ((progress.value - GlyphGone) / (1f - GlyphGone)).coerceIn(0f, 1f) }) {
					TvRemotePages(remote, state) { chrome, content ->
						RemotePanelFrame(
							chrome, content, with(density) { panelSize.toDpSize() }, modules,
							onModules = { modules = it; store.modules = it },
							mode = mode, onMode = { mode = it; store.mode = it },
							editing = editing, onEditing = { editing = it },
							onReset = {
								store.reset()
								modules = AllModules
								mode = PadMode.Dpad
								val rect = restoredPanel()
								panelOffset = rect.topLeft
								panelSize = rect.size
							},
							backEnabled = state.opened, input = remote::input, motion = motion,
						)
					}
				}
			}
		}
	}
}

/** True when the user has turned animations off, where the panel fades instead of growing out of the orb. */
private fun animationsOff(context: Context) =
	Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

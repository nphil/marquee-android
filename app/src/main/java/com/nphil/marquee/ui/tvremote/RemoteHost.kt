package com.nphil.marquee.ui.tvremote

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.TvRemote
import com.nphil.marquee.ui.shell.LocalShellMetrics
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lucent's container transform: the button and the panel are one surface, so one spring moves it. */
private val ContainerSpring = spring<Float>(dampingRatio = 1f, stiffness = 584f)

/** Where the panel opens until the user has moved or resized it. */
private const val DefaultWidth = 380f
private const val DefaultHeight = 660f

/** How far into the open the button's glyph has gone and the panel's content has come. */
private const val GlyphGone = 0.35f

/**
 * The remote's panel, over the app while it is open. It grows out of the button or mini bar that opened it and shrinks
 * back on close; [visible] is false outside the app's own screens. The panel exists only while the remote is enabled.
 */
@Composable
internal fun RemoteHost(remote: TvRemote, visible: Boolean) {
	val state by remote.state.collectAsStateWithLifecycle()
	val active = visible && state.enabled
	LaunchedEffect(active) { if (!active) remote.close() }
	if (active) RemotePanelHost(remote, state)
}

@Composable
private fun RemotePanelHost(remote: TvRemote, state: TvRemote.State) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val direction = LocalLayoutDirection.current
	val metrics = LocalShellMetrics.current
	val store = remember(context) { RemoteLayoutStore(context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)) }

	val gap = with(density) { Space.s2.toPx() }
	val grid = with(density) { RemoteMetrics.Grid.dp.toPx() }
	val minPanel = with(density) { RemoteMetrics.MinPanel.dp.toPx() }
	val seed = with(density) { 48.dp.toPx() }
	val lift = with(density) { 24.dp.toPx() }
	val sheetRadius = with(density) { Radius.sheet.toPx() }

	var modules by remember { mutableStateOf(store.modules) }
	var mode by remember { mutableStateOf(store.mode) }
	var editing by remember { mutableStateOf(false) }
	var shown by remember { mutableStateOf(false) }
	var reduced by remember { mutableStateOf(false) }
	// Read only inside layout and draw lambdas, so a drag or a resize does not recompose.
	var panelOffset by remember { mutableStateOf(Offset.Zero) }
	var panelSize by remember { mutableStateOf(Size.Zero) }
	var source by remember { mutableStateOf(Rect.Zero) }
	val progress = remember { Animatable(0f) }
	val fade = remember { Animatable(1f) }
	// The shell reports its bounds in window px; this overlay need not start at the window's corner.
	var windowOrigin by remember { mutableStateOf(Offset.Zero) }

	BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.positionInWindow() }) {
		val width = constraints.maxWidth.toFloat()
		val height = constraints.maxHeight.toFloat()
		val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
		val safe = Rect(
			bars.getLeft(density, direction).toFloat(), bars.getTop(density).toFloat(),
			width - bars.getRight(density, direction), height - bars.getBottom(density),
		)
		val bounds = safe.deflate(gap)

		fun restoredPanel(): Rect {
			store.panel?.let { return fitPanel(it.toRect(safe), bounds, minPanel) }
			val room = if (metrics.sidebarWidth > 0f) bounds.copy(left = max(bounds.left, metrics.sidebarWidth + gap)) else bounds
			return defaultPanel(room, with(density) { DefaultWidth.dp.toPx() }, with(density) { DefaultHeight.dp.toPx() })
		}

		/** The button's own rectangle, or a seed just above the bottom edge when a pairing link opened the panel. */
		fun openedFrom(): Rect = state.origin?.translate(-windowOrigin)
			?: Rect(Offset(safe.center.x - seed / 2, safe.bottom - lift - seed), Size(seed, seed))

		LaunchedEffect(state.opened) {
			if (state.opened) {
				source = openedFrom()
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
					fade.snapTo(1f)
				} else {
					progress.animateTo(0f, ContainerSpring)
				}
				shown = false
			}
		}
		// A new window size or insets (rotation, a fold) refits the open panel from its saved fractions.
		LaunchedEffect(safe) {
			if (shown) {
				val rect = fitPanel(restoredPanel(), bounds, minPanel)
				panelOffset = rect.topLeft
				panelSize = rect.size
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

		if (!shown) return@BoxWithConstraints
		val fill = lucent.sheet
		val seedFill = lucent.trackOff
		val seedEdge = lucent.edgeRaised
		val panelEdge = lucent.edge
		val hot = lucent.accentText
		fun containerBounds() = lerp(source, Rect(panelOffset, panelSize), progress.value)
		fun radius(p: Float, sizePx: Float) = lerp(min(source.width, source.height) / 2, sheetRadius.coerceAtMost(sizePx / 2), p)

		// An open panel is a surface of its own: its empty areas take touches rather than pass them to the app below.
		Box(
			Modifier
				.testTag("tv-remote-panel").semantics { paneTitle = "TV Remote" }
				.offset { containerBounds().topLeft.round() }
				.layout { measurable, _ ->
					val box = containerBounds()
					// The content is always laid out at the panel's full size and revealed by the clip.
					val full = Constraints(0, max(panelSize.width, source.width).roundToInt(), 0, max(panelSize.height, source.height).roundToInt())
					val placeable = measurable.measure(full)
					layout(box.width.roundToInt(), box.height.roundToInt()) { placeable.place(0, 0) }
				}
				.graphicsLayer {
					val p = progress.value
					alpha = if (reduced) fade.value else 1f
					shape = RoundedCornerShape(radius(p, min(size.width, size.height)))
					clip = true
					shadowElevation = p * 12.dp.toPx()
				}
				.drawBehind {
					val p = progress.value
					val r = radius(p, min(size.width, size.height))
					val stroke = 1.dp.toPx()
					drawRoundRect(lerp(seedFill, fill, p), cornerRadius = CornerRadius(r))
					drawRoundRect(
						lerp(seedEdge, panelEdge, p), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
						CornerRadius(max(r - stroke / 2, 0f)), Stroke(stroke),
					)
				}
				.pointerInput(Unit) {},
		) {
			val glyph = with(density) { min(source.width, source.height).toDp() }
			Box(Modifier.size(glyph).graphicsLayer { alpha = (1f - progress.value / GlyphGone).coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
				Icon(painterResource(R.drawable.ic_tv_remote), null, tint = hot)
			}
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

/** True when the user has turned animations off, where the panel fades instead of growing out of its button. */
private fun animationsOff(context: Context) =
	Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

package com.nphil.marquee.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag-to-reorder for a column of rows of one height inside a scrolling container. The grabbed row follows the finger
 * and the rows it passes make way; the move happens on release, so the list does not change under the finger. All rows
 * stay composed (use a plain column in a scroll container, not a lazy one), which is what lets the container scroll
 * under a held row.
 */
@Stable
class ReorderState internal constructor(
	private val scroll: ScrollState,
	private val rowHeight: Float,
	private val count: () -> Int,
	private val onMove: (from: Int, to: Int) -> Unit,
) {
	/** The index of the grabbed row, else -1. */
	var from by mutableIntStateOf(-1)
		private set

	private var drag by mutableFloatStateOf(0f)
	private var scrolled by mutableFloatStateOf(0f)

	/** Where the grabbed row's top was in the container when it was grabbed. */
	private var grabTop = 0f

	internal var container: LayoutCoordinates? = null
	internal val rows = HashMap<Int, LayoutCoordinates>()

	val active: Boolean get() = from >= 0

	/** How far the grabbed row is from its place: the finger's travel plus what the container has scrolled since. */
	internal val offset: Float get() = drag + scrolled

	/** The index the grabbed row takes when let go now. */
	private val to: Int
		get() = if (from < 0) -1 else (from + (offset / rowHeight).roundToInt()).coerceIn(0, count() - 1)

	/** How far row [index] stands from its place while another is held: one row for each row the held one has passed. */
	internal fun makeWay(index: Int): Float {
		val target = to
		return when {
			from < 0 || index == from -> 0f
			from < target && index in (from + 1)..target -> -rowHeight
			target < from && index in target until from -> rowHeight
			else -> 0f
		}
	}

	internal fun grab(index: Int) {
		val row = rows[index]?.takeIf { it.isAttached }
		val box = container?.takeIf { it.isAttached }
		grabTop = if (row != null && box != null) row.positionInRoot().y - box.positionInRoot().y else 0f
		drag = 0f
		scrolled = 0f
		from = index
	}

	internal fun dragBy(dy: Float) {
		drag += dy
	}

	/** Lets the row settle into the place it was dragged to, then moves it there. */
	internal suspend fun release() {
		val start = from
		if (start < 0) return
		val end = to
		animate(drag, (end - start) * rowHeight - scrolled, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ ->
			drag = value
		}
		cancel()
		if (start != end) onMove(start, end)
	}

	internal fun cancel() {
		from = -1
		drag = 0f
		scrolled = 0f
	}

	/** Scrolls the container a step while the held row is within [edge] px of its top or bottom. */
	internal suspend fun autoScroll(edge: Float, maxStep: Float) {
		val box = container?.takeIf { it.isAttached } ?: return
		if (from < 0) return
		val top = grabTop + drag
		val bottom = top + rowHeight
		val height = box.size.height.toFloat()
		val step = when {
			top < edge -> -maxStep * ((edge - top) / edge).coerceIn(0f, 1f)
			bottom > height - edge -> maxStep * ((bottom - (height - edge)) / edge).coerceIn(0f, 1f)
			else -> 0f
		}
		if (step != 0f) scrolled += scroll.scrollBy(step)
	}
}

/** The state for a column of rows [rowHeight] tall in [scroll]; [onMove] hears a finished drag. */
@Composable
fun rememberReorderState(scroll: ScrollState, rowHeight: Dp, count: Int, onMove: (from: Int, to: Int) -> Unit): ReorderState {
	val density = LocalDensity.current
	val rowPx = with(density) { rowHeight.toPx() }
	val latestCount by rememberUpdatedState(count)
	val latestMove by rememberUpdatedState(onMove)
	val state = remember(scroll, rowPx) { ReorderState(scroll, rowPx, { latestCount }, { from, to -> latestMove(from, to) }) }
	val edge = with(density) { EDGE.toPx() }
	val step = with(density) { STEP.toPx() }
	LaunchedEffect(state, state.active) {
		if (!state.active) return@LaunchedEffect
		while (true) {
			withFrameNanos { }
			state.autoScroll(edge, step)
		}
	}
	return state
}

/** Marks the scrolling container the rows move in. */
fun Modifier.reorderContainer(state: ReorderState): Modifier = onGloballyPositioned { state.container = it }

/** Puts a row where the drag has it, and makes way for the row held over it. */
@Composable
fun Modifier.reorderRow(state: ReorderState, index: Int): Modifier {
	val way by animateFloatAsState(
		targetValue = state.makeWay(index),
		// Once the move is made the rows are already in their new places: the shift ends at once, not by sliding back.
		animationSpec = if (state.active) spring(stiffness = Spring.StiffnessMediumLow) else snap(),
		label = "reorderWay",
	)
	val held = state.from == index
	return this
		.onGloballyPositioned { state.rows[index] = it }
		.zIndex(if (held) 1f else 0f)
		.graphicsLayer {
			translationY = if (held) state.offset else way
			val lift = if (held) 1.02f else 1f
			scaleX = lift
			scaleY = lift
		}
}

/** The grip of row [index]: a drag on it holds the row. */
@Composable
fun Modifier.reorderHandle(state: ReorderState, index: Int): Modifier {
	val scope = rememberCoroutineScope()
	return pointerInput(state, index) {
		detectDragGestures(
			onDragStart = { state.grab(index) },
			onDrag = { change, amount ->
				change.consume()
				state.dragBy(amount.y)
			},
			onDragEnd = { scope.launch { state.release() } },
			onDragCancel = { state.cancel() },
		)
	}
}

private val EDGE = 56.dp
private val STEP = 10.dp

package com.nphil.marquee.ui.shell

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// Widths in dp. The sidebar rests as the rail, or open at any width from OPEN_MIN_DP up to the window's limit; a width
// between the two is only passed through. OPEN_MIN_DP is where the widest label (the brand) has room.
internal const val RAIL_DP = 72f
internal const val OPEN_DP = 236f
internal const val OPEN_MIN_DP = 176f
private const val OPEN_MAX_DP = 360f

/** The sidebar never takes more than this share of the window, so the page keeps room. */
private const val WINDOW_SHARE = 0.4f

/** How far ahead a fling is projected when deciding between the rail and open. */
private const val THROW_SECONDS = 0.15f

/** The labels fade in between these widths, so they are never squeezed against the pane's edge. */
private const val LABELS_FROM_DP = 96f
private const val LABELS_FULL_DP = 156f

/** Closer than this to where it rests, the width just lands. */
private const val LANDED_DP = 0.1f

private val SidebarSpring = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold = LANDED_DP)

/** The widest the sidebar may be in a window [windowDp] wide, never narrower than the narrowest open width. */
internal fun sidebarLimit(windowDp: Float): Float = minOf(OPEN_MAX_DP, windowDp * WINDOW_SHARE).coerceAtLeast(OPEN_MIN_DP)

/**
 * Where the sidebar rests after a finger lets go of it at [width] dp moving [velocity] dp/s: the rail when it was dragged
 * or thrown narrow, otherwise the width it was left at.
 */
internal fun sidebarRest(width: Float, velocity: Float, limit: Float): Float {
	val thrown = width + velocity * THROW_SECONDS
	return if (thrown < (RAIL_DP + OPEN_MIN_DP) / 2) RAIL_DP else width.coerceIn(OPEN_MIN_DP, limit)
}

/** How visible the labels are at [width] dp: not at all in the rail, fully once the width has room for them. */
internal fun sidebarLabels(width: Float): Float = ((width - LABELS_FROM_DP) / (LABELS_FULL_DP - LABELS_FROM_DP)).coerceIn(0f, 1f)

/**
 * The sidebar's width: where it rests, and where it is while the toggle animates it or a finger drags it. [width] and
 * [moving] are state that only layout and draw read, so a drag recomposes nothing.
 */
@Stable
class SidebarSize(expanded: Boolean, open: Float) {
	/** Whether it rests open rather than as the rail. */
	var expanded by mutableStateOf(expanded)
		private set

	/** The width in dp it opens to: the last one the person chose. */
	var open by mutableFloatStateOf(open)
		private set

	/** True while a finger holds the handle. */
	var dragging by mutableStateOf(false)
		private set

	private var live by mutableFloatStateOf(if (expanded) open else RAIL_DP)
	private var limit by mutableFloatStateOf(OPEN_MAX_DP)
	private var density by mutableFloatStateOf(1f)
	private var inset by mutableFloatStateOf(0f)
	private var settling by mutableStateOf(false)

	// Tells a finished animation from one a newer drag or toggle has already replaced.
	private var run = 0
	private var job: Job? = null

	// The width when the finger took hold; the drag is measured from it.
	private var grab = 0f

	/** The width in dp right now, never past what the window allows. */
	val width: Float get() = live.coerceIn(RAIL_DP, limit)

	/** The widths a drag can reach, in dp. */
	internal val range: ClosedFloatingPointRange<Float> get() = RAIL_DP..limit

	/** The sidebar's width in px from the window's start edge, its start inset included. */
	val widthPx: Float get() = width * density + inset

	/** True while it is dragged or settling. The page beside it is laid out once, when this ends. */
	val moving: Boolean get() = dragging || settling

	/** Tells it the window's width in px, the density, and the start inset in px the sidebar adds to its own width. */
	internal fun fit(windowPx: Int, density: Float, inset: Float) {
		limit = sidebarLimit(windowPx / density)
		this.density = density
		this.inset = inset
	}

	fun toggle(scope: CoroutineScope) {
		expanded = !expanded
		settle(scope, if (expanded) open.coerceIn(OPEN_MIN_DP, limit) else RAIL_DP, 0f)
	}

	fun dragStarted() {
		run++
		job?.cancel()
		settling = false
		dragging = true
		grab = width
	}

	/** Follows the finger, which has travelled [dp] since it took hold. */
	fun dragBy(dp: Float) {
		live = (grab + dp).coerceIn(RAIL_DP, limit)
	}

	/** The finger let go moving [velocity] dp/s. */
	fun released(scope: CoroutineScope, velocity: Float) {
		dragging = false
		rest(scope, sidebarRest(width, velocity, limit), velocity)
	}

	/** Goes to [dp] as if dragged there and let go, for controls that cannot drag. */
	fun jumpTo(scope: CoroutineScope, dp: Float) = rest(scope, sidebarRest(dp, 0f, limit), 0f)

	private fun rest(scope: CoroutineScope, target: Float, velocity: Float) {
		expanded = target > RAIL_DP
		if (expanded) open = target
		settle(scope, target, velocity)
	}

	private fun settle(scope: CoroutineScope, target: Float, velocity: Float) {
		val mine = ++run
		job?.cancel()
		if (abs(target - live) < LANDED_DP) {
			live = target
			settling = false
			return
		}
		settling = true
		// Undispatched, so the page already sees it moving when the call returns.
		job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				// A fling that points away from where it rests would overshoot first.
				val carried = if ((target - live) * velocity > 0f) velocity else 0f
				animate(live, target, carried, SidebarSpring) { value, _ -> live = value }
			} finally {
				if (run == mine) settling = false
			}
		}
	}

	companion object {
		/** Keeps the person's choice across a recreated activity; the width itself is rebuilt at rest. */
		val Saver = listSaver<SidebarSize, Any>(
			save = { listOf(it.expanded, it.open) },
			restore = { SidebarSize(it[0] as Boolean, it[1] as Float) },
		)
	}
}

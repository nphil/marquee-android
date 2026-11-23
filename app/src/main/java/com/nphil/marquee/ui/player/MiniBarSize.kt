package com.nphil.marquee.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** The bar's height until it is dragged taller (dp): the picture's room, and the text and buttons beside it. */
internal const val MINI_BAR_DP = 56f

/** The tallest the bar gets, however large the window (dp). */
private const val MAX_DP = 160f

/** The share of the window's width the picture in the bar may take, so the title keeps its room beside it. */
private const val PICTURE_SHARE = 0.4f

/** The share of the window's height the bar may take, so the pages keep the rest. */
private const val HEIGHT_SHARE = 0.35f

/** A bar let go this close to its smallest height (dp) goes back to it. */
private const val SNAP_DP = 8f

private const val PICTURE_ASPECT = 16f / 9f

/** The tallest the bar may be in a window [width] by [height] dp. */
internal fun miniBarMax(width: Float, height: Float): Float =
	minOf(MAX_DP, width * PICTURE_SHARE / PICTURE_ASPECT, height * HEIGHT_SHARE).coerceAtLeast(MINI_BAR_DP)

/** Where a bar let go at [height] comes to rest: inside the limits, and back at its smallest when it is close to it. */
internal fun miniBarRest(height: Float, max: Float): Float {
	val inside = height.coerceIn(MINI_BAR_DP, max)
	return if (inside - MINI_BAR_DP <= SNAP_DP) MINI_BAR_DP else inside
}

/** The picture's width in a bar [height] tall: 16:9, rounded up to leave the text a margin. */
internal fun miniThumbWidth(height: Float): Float = ceil(height * PICTURE_ASPECT)

/**
 * How tall the person has made the bar, in dp. [room] is what the pages leave free at their foot and [height] is the
 * bar itself: they agree except during a drag, when the bar follows the finger and the pages wait for it to land, so a
 * drag moves nothing but the bar. [onChosen] gets each height that was let go, to be remembered.
 */
@Stable
internal class MiniBarSize(initial: Float = MINI_BAR_DP, private val onChosen: (Float) -> Unit = {}) {
	private var chosen by mutableFloatStateOf(initial)
	private var live by mutableFloatStateOf(initial)
	private var grab = initial
	private var limit by mutableFloatStateOf(Float.MAX_VALUE)

	var dragging by mutableStateOf(false)
		private set

	/** What the pages leave free at their foot. It changes when a drag lands, never during one. */
	val room: Dp get() = chosen.coerceIn(MINI_BAR_DP, limit).dp

	/** The bar's height, wherever the finger has it. Read in layout and draw only. */
	val height: Float get() = (if (dragging) live else chosen).coerceIn(MINI_BAR_DP, limit)

	val range: ClosedFloatingPointRange<Float> get() = MINI_BAR_DP..limit

	/** The window is [windowWidth] by [windowHeight] dp. */
	fun fit(windowWidth: Float, windowHeight: Float) {
		limit = miniBarMax(windowWidth, windowHeight)
	}

	/** The height remembered from before, unless the bar has been moved since. */
	fun restore(saved: Float?) {
		if (saved != null && !dragging) chosen = saved
	}

	fun dragStarted() {
		grab = height
		live = grab
		dragging = true
	}

	/** The finger is [dp] away from where the drag began; positive is taller. */
	fun dragBy(dp: Float) {
		live = (grab + dp).coerceIn(MINI_BAR_DP, limit)
	}

	fun dragEnded() = land(miniBarRest(live, limit))

	/** An assistive service asked for the bar to be [dp] tall. */
	fun adjustTo(dp: Float) = land(miniBarRest(dp, limit))

	private fun land(dp: Float) {
		chosen = dp
		dragging = false
		onChosen(dp)
	}
}

internal val LocalMiniBarSize = staticCompositionLocalOf { MiniBarSize() }

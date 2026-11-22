package com.nphil.marquee.ui.browse

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The least width of a poster in a shelf or a grid, by the width it has: a 104 dp poster is right at a phone's width and
 * a postage stamp on a tablet's. Whole dp, so the pixels asked of the server match on every screen.
 */
fun posterMinWidth(containerWidth: Dp): Dp = when {
	containerWidth >= 840.dp -> 148.dp
	containerWidth >= 600.dp -> 128.dp
	else -> Size.gridMin
}

/** The width of a Continue Watching card: most of a phone's screen, a third of a tablet's. */
fun continueCardWidth(containerWidth: Dp): Dp {
	val share = if (containerWidth < 600.dp) 0.82f else 0.33f
	return (max(containerWidth.value, 320f) * share).roundToInt().dp
}

/**
 * Columns of a poster grid: as many as fit at least [minCell] wide with [gutter] between, the cells filling the width.
 * At very large text sizes it is two columns, and one when even two do not fit, so a title stays readable.
 */
@Immutable
data class PosterGridMetrics(val columns: Int, val cellWidth: Dp, val gutter: Dp = Space.s3) {
	companion object {
		private const val LARGE_TEXT = 1.5f

		fun of(width: Dp, fontScale: Float = 1f, minCell: Dp = posterMinWidth(width), gutter: Dp = Space.s3): PosterGridMetrics {
			val usable = max(width.value, minCell.value)
			val fitting = max(1, ((usable + gutter.value) / (minCell.value + gutter.value)).toInt())
			val columns = when {
				fontScale < LARGE_TEXT -> fitting
				usable <= 240f -> 1
				else -> minOf(fitting, 2)
			}
			val cell = floor((usable - gutter.value * (columns - 1)) / columns)
			return PosterGridMetrics(columns, cell.dp, gutter)
		}
	}
}

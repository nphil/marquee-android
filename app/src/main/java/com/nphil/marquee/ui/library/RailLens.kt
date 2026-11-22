package com.nphil.marquee.ui.library

import com.nphil.marquee.ui.theme.Space
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where one rail letter sits in the magnifier, [distance] rows from the finger (an offset is negative above it). The
 * touched letter swells to 3.6× and leans 46 dp out over the grid, the letters beside it to 2.5× and 32 dp, then 1.75×
 * and 18 dp and 1.3× and 7 dp, and the rest dim to 72%; between rows it blends. That is stronger than Apollo's index
 * (2.9× and 27 dp), so the touched letter clears the thumb. Swollen letters would overlap at the rail's row pitch, so
 * the rows part from the finger until the gap between two capitals is a fixed margin; the rest of the rail keeps its
 * pitch, moved by what the lens added. With animations off only the touched letter grows, to a size that fits its row,
 * and nothing leans or moves.
 *
 * Lengths are in dp and carry no density, so the arithmetic is the same on every screen.
 */
internal object RailLens {
	/** The touched letter's scale, the largest any letter is drawn at. */
	const val PEAK = 3.6f

	private val scales = floatArrayOf(PEAK, 2.5f, 1.75f, 1.3f, 1f)
	private val leans = floatArrayOf(46f, 32f, 18f, 7f, 0f)
	private const val DIMMED = 0.72f

	/** The touched letter's scale with animations off: a capital 14.7 dp tall at the rail's 12 sp, inside its 16 dp row. */
	private const val STILL = 1.7f

	/** The rows the lens reaches past the touched one. */
	private const val REACH = 4f

	/** A capital's height as a share of its font size. */
	private const val CAP_HEIGHT = 0.72f

	/** The steps [position] sums over the rows the lens reaches; each is a quarter of the distance. */
	private const val STEPS = 4

	/** The clear space kept between two capitals. */
	private val margin = Space.s1.value

	private fun blend(table: FloatArray, distance: Float): Float {
		val clamped = min(distance, REACH)
		val lower = clamped.toInt()
		val upper = min(lower + 1, table.lastIndex)
		return table[lower] + (table[upper] - table[lower]) * (clamped - lower)
	}

	/** How many times its resting size the letter is drawn. */
	fun scale(distance: Float, still: Boolean): Float = when {
		!still -> blend(scales, distance)
		distance < 0.5f -> STILL
		else -> 1f
	}

	/** How far, in dp, the letter leans out toward the grid. */
	fun lean(distance: Float): Float = blend(leans, distance)

	/** How opaque the letter is: full to three rows out, then dimmed by the fourth. */
	fun opacity(distance: Float, still: Boolean): Float = when {
		!still -> 1f - (1f - DIMMED) * (distance - REACH + 1f).coerceIn(0f, 1f)
		distance < 0.5f -> 1f
		else -> DIMMED
	}

	/** The height a capital shows at [distance] rows from the finger, for a font size of [glyph] dp. */
	fun capHeight(distance: Float, glyph: Float): Float = glyph * CAP_HEIGHT * blend(scales, distance)

	/**
	 * How far [distance] rows from the finger a letter sits once the rows are parted, for rows of [row] dp. Each step of
	 * one row grows to the height of the capital there plus the margin and never shrinks below the row; past the lens
	 * the step stays that of the letter at rest.
	 */
	fun position(distance: Float, row: Float, glyph: Float): Float {
		val step = min(distance, REACH) / STEPS
		var total = 0f
		for (k in 0 until STEPS) total += max(row, capHeight((k + 0.5f) * step, glyph) + margin) * step
		return total + max(distance - REACH, 0f) * max(row, capHeight(REACH, glyph) + margin)
	}

	/** How far down (or up, for a negative [offset]) the lens moves a letter from its own row: away from the finger. */
	fun shift(offset: Float, row: Float, glyph: Float): Float {
		val distance = abs(offset)
		return (position(distance, row, glyph) - distance * row) * if (offset < 0f) -1f else 1f
	}

	/** The panel behind the lens: tall enough for the touched letter and two either side, which spread apart as they swell. */
	fun panelHeight(row: Float, glyph: Float, minimum: Float): Float =
		max(minimum, 2f * (position(2f, row, glyph) + capHeight(2f, glyph) / 2f + margin))
}

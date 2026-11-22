package com.nphil.marquee.playback

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.max

/**
 * How much of each edge of a video frame is a black bar baked into the picture, as a fraction of the frame's width
 * (left, right) or height (top, bottom).
 */
@Immutable
internal data class Bars(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
	/** The thicker bar at each edge. */
	fun thicker(other: Bars) = Bars(max(left, other.left), max(top, other.top), max(right, other.right), max(bottom, other.bottom))

	companion object {
		val None = Bars()

		/** A bar thinner than this is the frame's own edge, not a bar. */
		const val MIN = 0.015f
	}
}

/** What one look at a frame found. */
internal sealed interface Reading {
	/** Nothing has been drawn into the surface yet. */
	data object Undrawn : Reading

	/**
	 * No evidence of bars either way: [dark], almost nothing in the frame is bright (a fade, an end card, a night shot,
	 * where a dark picture is easily taken for bars), or the frame does not look like a letterbox.
	 */
	data class Unclear(val dark: Boolean) : Reading

	/** The bars on the frame's edges, [Bars.None] when the picture runs to all four. */
	data class Clear(val bars: Bars) : Reading
}

/**
 * Finds the black bars baked into a video frame from a small copy of it (a few thousand pixels, sampled a few times a
 * second, never per frame). A bar is a run of rows or columns from an edge that hold nothing lit; it counts only if
 * the bars at the two ends of an axis match, because a letterbox is centred and a dark sky or a dark floor is not.
 * Anything else, a frame that is nearly black included, is [Reading.Unclear], so a fade to black or a night scene
 * never moves the crop. One reader serves one thread.
 */
internal class PictureReader {
	private var rows = IntArray(0)
	private var columns = IntArray(0)

	/** [pixels] is [width] x [height] ARGB, row by row. */
	fun read(pixels: IntArray, width: Int, height: Int): Reading {
		// A surface that has had no frame copies as transparent; every frame of a video is opaque.
		if (pixels[(height / 2) * width + width / 2] ushr 24 == 0) return Reading.Undrawn
		if (rows.size == height) rows.fill(0) else rows = IntArray(height)
		if (columns.size == width) columns.fill(0) else columns = IntArray(width)

		var bright = 0
		var index = 0
		for (y in 0 until height) {
			for (x in 0 until width) {
				val pixel = pixels[index++]
				val luma = (77 * ((pixel shr 16) and 0xFF) + 150 * ((pixel shr 8) and 0xFF) + 29 * (pixel and 0xFF)) shr 8
				if (luma > BLACK) {
					rows[y]++
					columns[x]++
					if (luma > BRIGHT) bright++
				}
			}
		}
		if (bright < width * height * DARK_SHARE) return Reading.Unclear(dark = true)

		val rowLimit = (height * MAX_ROWS).toInt()
		val columnLimit = (width * MAX_COLUMNS).toInt()
		val top = blackRun(rows, width, fromEnd = false, limit = rowLimit)
		val bottom = blackRun(rows, width, fromEnd = true, limit = rowLimit)
		val left = blackRun(columns, height, fromEnd = false, limit = columnLimit)
		val right = blackRun(columns, height, fromEnd = true, limit = columnLimit)
		if (top > rowLimit || bottom > rowLimit || left > columnLimit || right > columnLimit) return Reading.Unclear(dark = false)

		val bars = Bars(left = left / width.toFloat(), top = top / height.toFloat(), right = right / width.toFloat(), bottom = bottom / height.toFloat())
		if (!centred(bars.top, bars.bottom) || !centred(bars.left, bars.right)) return Reading.Unclear(dark = false)
		return Reading.Clear(Bars(thin(bars.left), thin(bars.top), thin(bars.right), thin(bars.bottom)))
	}

	/**
	 * How many lines from one end of [lit] hold nothing lit, counting at most [limit] + 1. A few lit pixels do not
	 * make a line picture: compression leaves specks in a bar, and a logo sits in one.
	 */
	private fun blackRun(lit: IntArray, across: Int, fromEnd: Boolean, limit: Int): Int {
		val allowed = (across * LIT_SHARE).toInt()
		var run = 0
		while (run <= limit && run < lit.size) {
			val line = if (fromEnd) lit.size - 1 - run else run
			if (lit[line] > allowed) break
			run++
		}
		return run
	}

	private fun thin(bar: Float) = if (bar < Bars.MIN) 0f else bar

	/** The two bars of an axis are both too thin to count, or both count and are about as thick as each other. */
	private fun centred(start: Float, end: Float): Boolean {
		if (start < Bars.MIN && end < Bars.MIN) return true
		return start >= Bars.MIN && end >= Bars.MIN && abs(start - end) <= max(SYMMETRY, start.coerceAtLeast(end) * SYMMETRY_SHARE)
	}

	private companion object {
		/** Letterbox black decodes to 0..16 and noise adds a little: up to here a pixel is black. */
		const val BLACK = 24

		/** Above this a pixel is bright; a frame with almost none is a fade, an end card or a night shot. */
		const val BRIGHT = 48

		/** A frame with less than this share of its pixels bright is dark, and says nothing about bars. */
		const val DARK_SHARE = 0.02f

		/** A line with up to this share of its pixels lit is still a bar. */
		const val LIT_SHARE = 0.03f

		/** The widest letterbox (2.76:1 in 16:9 is 0.18) and pillarbox (1:1 in 16:9 is 0.22) that are believed. */
		const val MAX_ROWS = 0.2f
		const val MAX_COLUMNS = 0.25f

		/** How different the bars at the two ends of an axis may be: this much, or this share of the thicker one. */
		const val SYMMETRY = 0.03f
		const val SYMMETRY_SHARE = 0.25f
	}
}

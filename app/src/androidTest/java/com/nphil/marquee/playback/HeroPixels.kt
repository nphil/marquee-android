package com.nphil.marquee.playback

import android.graphics.Bitmap
import android.graphics.Rect

/** A side of a hero. */
internal enum class Side { Top, Bottom, Left, Right }

/**
 * A strip of a captured hero, looked at as a person looks for a bar: how bright it is on average and how much of it is
 * black. A trailer's picture is bright all over; a bar that was not cropped away is a band of black.
 */
internal class Strip(val mean: Float, val black: Float) {
	val isPicture: Boolean get() = mean >= MinMean && black <= MaxBlack

	override fun toString() = "mean %.0f, %.1f%% black".format(mean, black * 100)

	companion object {
		/** The darkest edge strip of any clip's picture averages a luma of 107 (ffmpeg's signalstats), so a scrim may dim it by 40%. */
		const val MinMean = 64
		const val MaxBlack = 0.02f
	}
}

/**
 * The strip along [side], [inset] pixels in from the edge (a card's own border is not the video's), a thirtieth of the
 * hero thick and not less than 4 pixels, over the middle 80% of that side so a card's rounded corners are left out.
 */
internal fun Bitmap.strip(side: Side, inset: Int = 0): Strip {
	val horizontal = side == Side.Top || side == Side.Bottom
	val depth = maxOf(MinDepth, (if (horizontal) height else width) / DepthShare)
	val area = when (side) {
		Side.Top -> Rect(width / 10, inset, width * 9 / 10, inset + depth)
		Side.Bottom -> Rect(width / 10, height - inset - depth, width * 9 / 10, height - inset)
		Side.Left -> Rect(inset, height / 10, inset + depth, height * 9 / 10)
		Side.Right -> Rect(width - inset - depth, height / 10, width - inset, height * 9 / 10)
	}
	return measure(area)
}

/** The middle fifth of the hero. */
internal fun Bitmap.centre(): Strip = measure(Rect(width * 2 / 5, height * 2 / 5, width * 3 / 5, height * 3 / 5))

/** The thickness of the black band at the top, in pixels: the rows from the edge down that are black along their whole middle. */
internal fun Bitmap.blackTop(): Int {
	val row = IntArray(width * 8 / 10)
	var rows = 0
	while (rows < height) {
		getPixels(row, 0, row.size, width / 10, rows, row.size, 1)
		if (row.count { luma(it) >= BlackBelow } > row.size * MaxBlackRowLit) break
		rows++
	}
	return rows
}

private fun Bitmap.measure(area: Rect): Strip {
	val pixels = IntArray(area.width() * area.height())
	getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
	var sum = 0L
	var black = 0
	for (pixel in pixels) {
		val luma = luma(pixel)
		sum += luma
		if (luma < BlackBelow) black++
	}
	return Strip(sum / pixels.size.toFloat(), black / pixels.size.toFloat())
}

private fun luma(pixel: Int) = (77 * ((pixel shr 16) and 0xFF) + 150 * ((pixel shr 8) and 0xFF) + 29 * (pixel and 0xFF)) shr 8

private const val MinDepth = 4
private const val DepthShare = 30

/** What a bar's pixels measure after the video has been decoded, scaled and composited: black is 0, noise is a few levels. */
private const val BlackBelow = 32

/** A row is part of a black band while no more than this share of it is lit. */
private const val MaxBlackRowLit = 0.03f

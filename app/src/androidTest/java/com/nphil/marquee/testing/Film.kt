package com.nphil.marquee.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

/**
 * A page closing, as one picture per frame. Each frame is placed between the first picture (the page) and the last
 * (the screen it reveals): 0 is still the page, 1 is the screen under it. A smooth close rises evenly. A page left
 * standing until it is cut away shows as a flat run and then one big step, and an animation that starts over shows
 * as a step back.
 */
class Film {
	private val pictures = mutableListOf<IntArray>()
	private var width = 0
	private var height = 0

	val size: Int get() = pictures.size

	/** Keeps a small copy of what the screen shows now; a whole close at full size would not fit in memory. */
	fun add(frame: Bitmap) {
		pictures += shrink(frame)
	}

	/** Where each frame is between the first and the last. */
	val place: List<Float>
		get() = pictures.map { picture ->
			val fromFirst = gap(picture, pictures.first())
			val fromLast = gap(picture, pictures.last())
			if (fromFirst + fromLast == 0f) 0f else fromFirst / (fromFirst + fromLast)
		}

	/** The largest move forward between two neighbouring frames. */
	val biggestStep: Float get() = place.zipWithNext { before, after -> after - before }.maxOrNull() ?: 0f

	/** The largest move back between two neighbouring frames. */
	val biggestStepBack: Float get() = place.zipWithNext { before, after -> before - after }.maxOrNull()?.coerceAtLeast(0f) ?: 0f

	/**
	 * The least that any [frames] frames in a row gained, from frame [from] on, among those that start short of [until]
	 * (a window near the end has little left to gain). A page left standing gains close to nothing.
	 */
	fun slowestGain(from: Int, frames: Int = 8, until: Float = 0.8f): Float {
		val place = place
		var slowest = 1f
		for (i in from until place.size) {
			if (place[i] >= until) break
			slowest = minOf(slowest, place[minOf(i + frames, place.lastIndex)] - place[i])
		}
		return slowest
	}

	/** How far any frame got from the first: 0 for the same picture, 1 for black against white. */
	val reach: Float get() = pictures.maxOfOrNull { gap(it, pictures.first()) } ?: 0f

	/** How far the last frame is from the first: a swipe called off must end where it began. */
	val leftover: Float get() = if (pictures.isEmpty()) 0f else gap(pictures.last(), pictures.first())

	/** Every frame, small and numbered, in reading order: what a person looks at to see the close. [marked] is drawn red. */
	fun contactSheet(columns: Int = 10, marked: Int = -1): Bitmap {
		val rows = (pictures.size + columns - 1) / columns
		val sheet = Bitmap.createBitmap(columns * (width + Gap), rows * (height + Gap), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(sheet)
		canvas.drawColor(Color.DKGRAY)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width / 5f }
		pictures.forEachIndexed { i, pixels ->
			val x = (i % columns) * (width + Gap)
			val y = (i / columns) * (height + Gap)
			val tile = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
			canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
			tile.recycle()
			paint.color = if (i == marked) Color.RED else Color.WHITE
			canvas.drawText("$i", x + 4f, y + paint.textSize, paint)
		}
		return sheet
	}

	override fun toString() = place.joinToString(" ") { "%.2f".format(it) }

	private fun shrink(frame: Bitmap): IntArray {
		if (pictures.isEmpty()) {
			width = Width
			height = Width * frame.height / frame.width
		}
		var small = frame
		// Halving averages 2x2 pixels at a time; one big step would sample a few pixels and lose thin things.
		while (small.width >= 2 * width && small.height >= 2 * height) {
			val half = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
			if (small !== frame) small.recycle()
			small = half
		}
		val scaled = Bitmap.createScaledBitmap(small, width, height, true)
		val pixels = IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) }
		if (small !== frame && small !== scaled) small.recycle()
		if (scaled !== frame) scaled.recycle()
		return pixels
	}

	private companion object {
		/** A tenth of a phone's width in pixels, so ten frames make a row as wide as the screen. */
		const val Width = 108
		const val Gap = 2

		/** The mean difference of two pictures: 0 for the same, 1 for black against white. */
		fun gap(a: IntArray, b: IntArray): Float {
			var sum = 0L
			for (i in a.indices) {
				val x = a[i]
				val y = b[i]
				sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) +
					abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) +
					abs((x and 0xFF) - (y and 0xFF))
			}
			return sum / (a.size * 765f)
		}
	}
}

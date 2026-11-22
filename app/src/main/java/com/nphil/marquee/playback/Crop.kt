package com.nphil.marquee.playback

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the looks at a trailer into the crop to hold. A bar is believed once two looks in a row agree, so one odd
 * frame (a flash, a shot with a black edge) does not move the picture, and a cut to another aspect ratio is followed
 * within a couple of looks. A confirmed bar that differs only a little from the one in force is left alone: the
 * crop does not drift with noise.
 */
internal class CropTracker {
	/** The bars cropped away now. */
	var bars = Bars.None
		private set

	/** A bar, or the lack of one, has been confirmed at least once. */
	var settled = false
		private set

	/** The last looks agree, so the next need not come soon. */
	val sure: Boolean get() = votes >= CONFIRM

	private var candidate = Bars.None
	private var votes = 0

	/** Takes one clear look; true when the crop in force [bars] changed. */
	fun accept(seen: Bars): Boolean {
		if (votes > 0 && agree(seen, candidate)) {
			// The thicker of agreeing looks: cropping a hair too much is invisible, a sliver of bar is not.
			candidate = candidate.thicker(seen)
			votes = min(votes + 1, CONFIRM)
		} else {
			candidate = seen
			votes = 1
		}
		if (votes < CONFIRM) return false
		settled = true
		if (fits(candidate)) return false
		bars = candidate
		return true
	}

	private fun agree(a: Bars, b: Bars) =
		abs(a.left - b.left) <= AGREE && abs(a.top - b.top) <= AGREE && abs(a.right - b.right) <= AGREE && abs(a.bottom - b.bottom) <= AGREE

	/** The crop in force already does for [next]: no bar is thicker than it covers, and none is much thinner. */
	private fun fits(next: Bars) =
		fits(next.left - bars.left) && fits(next.top - bars.top) && fits(next.right - bars.right) && fits(next.bottom - bars.bottom)

	private fun fits(change: Float) = change <= GROW && change >= -SHRINK

	private companion object {
		const val CONFIRM = 2

		/** Looks closer than this on every edge are the same bars. */
		const val AGREE = 0.02f

		/** A bar thicker than the crop by this much shows; one thinner by this much costs picture nobody misses. */
		const val GROW = 0.006f
		const val SHRINK = 0.02f
	}
}

/** Where a trailer's picture goes in the hero so that its bars fall outside it. */
internal object Fill {
	/** How the picture is moved: scaled about the middle of the frame, then shifted by ([shiftX], [shiftY]) pixels. */
	data class Placement(val scale: Float, val shiftX: Float, val shiftY: Float)

	/**
	 * The picture's active area is cut a hair inside each bar, so the soft line where a bar meets the picture, which
	 * the encoder and the scaler blur across a pixel or two, is not shown. It grows in with the bar, so a bar that is
	 * only just appearing does not make the picture jump.
	 */
	const val MARGIN = 0.012f

	/**
	 * Fills a hero of [heroWidth] x [heroHeight] with what is inside [bars] of a frame of [frameWidth] x [frameHeight],
	 * both in pixels as laid out: the frame is already scaled to cover the hero, so a frame with no bars needs nothing.
	 * The scale never goes below 1, which would uncover the hero.
	 */
	fun place(heroWidth: Float, heroHeight: Float, frameWidth: Float, frameHeight: Float, bars: Bars): Placement {
		val left = inset(bars.left)
		val right = 1f - inset(bars.right)
		val top = inset(bars.top)
		val bottom = 1f - inset(bars.bottom)
		val scale = max(1f, max(heroWidth / ((right - left) * frameWidth), heroHeight / ((bottom - top) * frameHeight)))
		return Placement(
			scale = scale,
			shiftX = -((left + right) / 2f - 0.5f) * frameWidth * scale,
			shiftY = -((top + bottom) / 2f - 0.5f) * frameHeight * scale,
		)
	}

	private fun inset(bar: Float) = if (bar <= 0f) 0f else bar + MARGIN * min(1f, bar / Bars.MIN)
}

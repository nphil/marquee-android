package com.nphil.marquee.playback

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import com.nphil.marquee.testing.Film
import kotlin.math.abs
import org.junit.Assert.assertTrue

/** How far a measured crop may be from the clip's own bars: a sample row is 1.1% of the frame, and the crop keeps a margin. */
private const val BarsTolerance = 0.04f

/** What the playback tests ask of the hero on screen: the state of the trailer's picture, and shots of the hero. */
internal class HeroProbe(private val rule: ComposeTestRule, private val heroTag: String) {
	/** The trailer picture's state as it is now, or null while the trailer is not on screen. */
	fun state(): PictureState? =
		rule.onAllNodes(SemanticsMatcher.keyIsDefined(PictureStateKey)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(PictureStateKey)?.invoke()

	fun picture(): PictureState = checkNotNull(state()) { "the trailer's picture is not on screen" }

	/** Waits for [condition], in real time; a timeout says what the picture was doing. */
	fun await(what: String, millis: Long, condition: () -> Boolean) {
		try {
			rule.waitUntil(what, millis, condition)
		} catch (timeout: ComposeTimeoutException) {
			throw AssertionError("${timeout.message}. The picture is ${state()}.", timeout)
		}
	}

	/** Shoots the hero into each film in [into] and hands the shot to [measure]. */
	fun <T> shoot(vararg into: Film, measure: (Bitmap) -> T): T {
		val shot = rule.onAllNodesWithTag(heroTag).onFirst().captureToImage().asAndroidBitmap()
		try {
			into.forEach { it.add(shot) }
			return measure(shot)
		} finally {
			shot.recycle()
		}
	}

	/**
	 * Moves the clock a frame at a time, shooting the hero, until the picture has begun to show and [fadeFrames] shots
	 * have been taken from that frame on, or [millis] of real time have passed. [film] gets the first [artFrames] shots
	 * of the art alone, unless it has them already, and every shot from the picture's first frame; [quiet] gets every
	 * shot before that. A long wait for the decoder would otherwise fill the contact sheet and the heap with the same
	 * picture. Returns the frame of [film] the picture began to show at, or -1 if it never did.
	 */
	fun filmFade(film: Film, quiet: Film, artFrames: Int, fadeFrames: Int, millis: Long): Int {
		var shownAt = -1
		val end = System.currentTimeMillis() + millis
		while (System.currentTimeMillis() < end && (shownAt < 0 || film.size < shownAt + fadeFrames)) {
			rule.mainClock.advanceTimeByFrame()
			if (shownAt < 0 && state()?.shown == true) shownAt = film.size
			when {
				shownAt >= 0 -> shoot(film) {}
				film.size < artFrames -> shoot(film, quiet) {}
				else -> shoot(quiet) {}
			}
		}
		return shownAt
	}

	fun assertBars(what: String, expected: Bars, actual: Bars) {
		val close = abs(actual.left - expected.left) <= BarsTolerance && abs(actual.top - expected.top) <= BarsTolerance &&
			abs(actual.right - expected.right) <= BarsTolerance && abs(actual.bottom - expected.bottom) <= BarsTolerance
		assertTrue("$what: the crop was made for $actual, the clip has $expected (within $BarsTolerance)", close)
	}

	/** Every side of [hero] and its middle show picture. With no [inset] a single row of black along an edge is enough to fail. */
	fun assertFilled(what: String, hero: Bitmap, inset: Int = 0) {
		for (side in Side.entries) {
			val strip = hero.strip(side, inset)
			assertTrue("$what: the hero's ${side.name.lowercase()} is not picture, it is $strip", strip.isPicture)
		}
		val centre = hero.centre()
		assertTrue("$what: the hero's middle is not picture, it is $centre", centre.isPicture)
	}
}

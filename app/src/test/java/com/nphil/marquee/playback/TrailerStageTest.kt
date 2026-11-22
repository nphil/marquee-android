package com.nphil.marquee.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The looks come 100 ms apart on a clock of the test's own; the trailer is 30 s long and 1 s in unless said. */
class TrailerStageTest {
	private val stage = PictureStage()
	private val scope = Reading.Clear(Bars(top = 0.133f, bottom = 0.133f))
	private val plain = Reading.Clear(Bars.None)
	private val dark = Reading.Unclear(dark = true)
	private val odd = Reading.Unclear(dark = false)
	private var now = 1_000L

	@Test
	fun theFadeIsOverBeforeTheTrailerIs() {
		assertTrue(TrailerFade.LEAD_MILLIS > TrailerFade.MILLIS)
	}

	@Test
	fun isLetInOnceTheCropIsSettledOnALitFrame() {
		look(scope)
		assertFalse(stage.shown)
		look(scope)
		assertTrue(stage.shown)
		assertEquals(Bars(top = 0.133f, bottom = 0.133f), stage.bars)
	}

	@Test
	fun aPictureWithoutBarsIsLetInWithoutACrop() {
		look(plain)
		look(plain)
		assertTrue(stage.shown)
		assertEquals(Bars.None, stage.bars)
	}

	@Test
	fun aDarkOpeningIsWaitedOutAndThenLetInAnyway() {
		// The first look is at 1.1 s, and the picture is let in 1.5 s later.
		repeat(15) {
			look(dark)
			assertFalse(stage.shown)
		}
		look(dark)
		assertTrue(stage.shown)
	}

	@Test
	fun aDarkFrameHoldsItBackUntilALitOneSettles() {
		repeat(3) { look(dark) }
		look(plain)
		assertFalse(stage.shown)
		look(plain)
		assertTrue(stage.shown)
	}

	@Test
	fun looksThatSayNothingNeverMoveTheCrop() {
		repeat(2) { look(plain) }
		repeat(5) { look(odd) }
		repeat(5) { look(dark) }
		assertEquals(Bars.None, stage.bars)
	}

	@Test
	fun followsAChangeOfBarsOnceItIsShown() {
		repeat(2) { look(plain) }
		repeat(2) { look(scope) }
		assertEquals(Bars(top = 0.133f, bottom = 0.133f), stage.bars)
		assertTrue(stage.visible)
	}

	@Test
	fun leavesAheadOfTheEndSoNoClosingCardIsShown() {
		show()
		look(plain, position = 29_400L)
		assertTrue(stage.visible)
		look(plain, position = 29_550L)
		assertFalse(stage.visible)
		assertTrue(stage.ending)
	}

	@Test
	fun leavesAtOnceAtADarkEndCardButNotAtADarkMomentEarlier() {
		show()
		look(dark, position = 20_000L)
		assertTrue(stage.visible)
		look(dark, position = 26_500L)
		assertFalse(stage.visible)
	}

	@Test
	fun staysWhileItsLengthIsNotKnown() {
		show()
		look(dark, position = 29_900L, duration = C.TIME_UNSET)
		assertTrue(stage.visible)
	}

	@Test
	fun looksQuicklyUntilItIsSureAndThenSeldom() {
		assertEquals(TrailerFade.EAGER_MILLIS, look(plain))
		assertEquals(TrailerFade.STEADY_MILLIS, look(plain))
		assertEquals(TrailerFade.EAGER_MILLIS, look(scope))
		assertEquals(TrailerFade.STEADY_MILLIS, look(scope))
	}

	private fun show() = repeat(2) { look(plain) }

	private fun look(reading: Reading, position: Long = 1_000L, duration: Long = 30_000L): Long {
		now += 100L
		return stage.look(reading, now, position, duration)
	}
}

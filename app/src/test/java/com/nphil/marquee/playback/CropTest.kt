package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropTrackerTest {
	private val tracker = CropTracker()
	private val scope = Bars(top = 0.133f, bottom = 0.133f)

	@Test
	fun oneLookIsNotBelieved() {
		assertFalse(tracker.accept(scope))
		assertEquals(Bars.None, tracker.bars)
		assertFalse(tracker.settled)
	}

	@Test
	fun twoLooksThatAgreeSetTheCrop() {
		tracker.accept(scope)
		assertTrue(tracker.accept(scope))
		assertEquals(scope, tracker.bars)
		assertTrue(tracker.settled)
	}

	@Test
	fun theLackOfBarsIsSettledToo() {
		tracker.accept(Bars.None)
		assertFalse(tracker.accept(Bars.None))
		assertTrue(tracker.settled)
		assertEquals(Bars.None, tracker.bars)
	}

	@Test
	fun oneOddLookAmongPlainOnesChangesNothing() {
		repeat(2) { tracker.accept(Bars.None) }
		tracker.accept(scope)
		tracker.accept(Bars.None)
		assertFalse(tracker.accept(Bars.None))
		assertEquals(Bars.None, tracker.bars)
	}

	@Test
	fun followsACutToAnotherAspectRatioAndBack() {
		repeat(2) { tracker.accept(Bars.None) }
		repeat(2) { tracker.accept(scope) }
		assertEquals(scope, tracker.bars)
		repeat(2) { tracker.accept(Bars.None) }
		assertEquals(Bars.None, tracker.bars)
	}

	@Test
	fun aBarALittleThinnerKeepsTheCropAndOneThickerGrowsIt() {
		repeat(2) { tracker.accept(scope) }
		repeat(2) { tracker.accept(Bars(top = 0.12f, bottom = 0.12f)) }
		assertEquals(scope, tracker.bars)
		repeat(2) { tracker.accept(Bars(top = 0.15f, bottom = 0.15f)) }
		assertEquals(Bars(top = 0.15f, bottom = 0.15f), tracker.bars)
	}

	@Test
	fun asksForQuickLooksUntilTheLooksAgree() {
		assertFalse(tracker.sure)
		tracker.accept(scope)
		assertFalse(tracker.sure)
		tracker.accept(scope)
		assertTrue(tracker.sure)
		tracker.accept(Bars.None)
		assertFalse(tracker.sure)
	}
}

class FillTest {
	@Test
	fun aFrameWithoutBarsIsLeftAlone() {
		val at = Fill.place(640f, 360f, 640f, 360f, Bars.None)
		assertEquals(1f, at.scale, EPSILON)
		assertEquals(0f, at.shiftX, EPSILON)
		assertEquals(0f, at.shiftY, EPSILON)
	}

	@Test
	fun aWideVideoWithoutBarsIsOnlyCentredLikeAspectFill() {
		// Laid out to cover the hero, a 2.39:1 frame is wider than it: it needs no more.
		val at = Fill.place(640f, 360f, 861f, 360f, Bars.None)
		assertEquals(1f, at.scale, EPSILON)
	}

	@Test
	fun aLetterboxIsZoomedUntilItsPictureFillsTheHero() {
		val at = Fill.place(640f, 360f, 640f, 360f, Bars(top = 12 / 90f, bottom = 12 / 90f))
		assertEquals(1f / (1f - 2f * (12 / 90f + Fill.MARGIN)), at.scale, EPSILON)
		assertEquals(0f, at.shiftY, EPSILON)
	}

	@Test
	fun aPillarboxIsZoomedUntilItsPictureFillsTheHero() {
		val at = Fill.place(640f, 360f, 640f, 360f, Bars(left = 0.125f, right = 0.125f))
		assertEquals(1f / (1f - 2f * (0.125f + Fill.MARGIN)), at.scale, EPSILON)
		assertEquals(0f, at.shiftX, EPSILON)
	}

	@Test
	fun aLopsidedLetterboxShiftsItsPictureToTheMiddle() {
		val at = Fill.place(640f, 360f, 640f, 360f, Bars(top = 0.2f, bottom = 0.1f))
		val scale = 1f / (1f - (0.2f + Fill.MARGIN) - (0.1f + Fill.MARGIN))
		assertEquals(scale, at.scale, EPSILON)
		// The picture's middle is a twentieth of the frame below the frame's middle: up it goes.
		assertEquals(-0.05f * 360f * scale, at.shiftY, 0.01f)
	}

	@Test
	fun theHeroIsInsideThePictureWhateverTheBarsAndAspectRatios() {
		val random = Random(3)
		repeat(500) {
			val heroWidth = random.nextInt(300, 1200).toFloat()
			val heroHeight = heroWidth * 9f / 16f
			val aspect = 1.2f + random.nextFloat() * 1.3f
			// The frame as the player lays it out: it covers the hero and overflows it.
			val frameHeight = if (aspect >= 16f / 9f) heroHeight else heroWidth / aspect
			val frameWidth = frameHeight * aspect
			val bars = Bars(random.nextFloat() * 0.25f, random.nextFloat() * 0.2f, random.nextFloat() * 0.25f, random.nextFloat() * 0.2f)
			val at = Fill.place(heroWidth, heroHeight, frameWidth, frameHeight, bars)
			for ((x, y) in listOf(0f to 0f, heroWidth to 0f, 0f to heroHeight, heroWidth to heroHeight)) {
				// Where this corner of the hero falls in the frame, from the middle of the hero and the middle of the frame.
				val u = 0.5f + (x - heroWidth / 2 - at.shiftX) / at.scale / frameWidth
				val v = 0.5f + (y - heroHeight / 2 - at.shiftY) / at.scale / frameHeight
				val where = "corner $x,$y of $heroWidth x $heroHeight at $u,$v with $bars"
				assertTrue(where, u >= bars.left - EPSILON && u <= 1f - bars.right + EPSILON)
				assertTrue(where, v >= bars.top - EPSILON && v <= 1f - bars.bottom + EPSILON)
			}
		}
	}

	@Test
	fun aBarThatIsJustAppearingDoesNotMakeThePictureJump() {
		var last = Fill.place(640f, 360f, 640f, 360f, Bars.None).scale
		var bar = 0f
		while (bar <= 0.2f) {
			bar += 0.002f
			val scale = Fill.place(640f, 360f, 640f, 360f, Bars(top = bar, bottom = bar)).scale
			assertTrue("bar $bar: $last to $scale", scale >= last && scale - last < 0.02f)
			last = scale
		}
	}

	private companion object {
		const val EPSILON = 1e-4f
	}
}

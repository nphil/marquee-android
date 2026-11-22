package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Synthetic frames of 160 x 90, the size the app reads, each pixel given a brightness from 0 to 255. */
class ActivePictureTest {
	private val reader = PictureReader()

	@Test
	fun aPictureThatRunsToEveryEdgeHasNoBars() {
		assertEquals(Reading.Clear(Bars.None), look(frame { x, y -> picture(x, y) }))
	}

	@Test
	fun aLetterboxIsMeasuredFromItsRowsOfBlack() {
		// 2.39:1 in 16:9 leaves 12 of 90 rows at the top and at the bottom.
		assertEquals(Reading.Clear(Bars(top = 12 / 90f, bottom = 12 / 90f)), look(letterbox(12, 12)))
	}

	@Test
	fun aPillarboxIsMeasuredFromItsColumnsOfBlack() {
		// 4:3 in 16:9 leaves 20 of 160 columns at each side.
		assertEquals(Reading.Clear(Bars(left = 20 / 160f, right = 20 / 160f)), look(pillarbox(20)))
	}

	@Test
	fun aWindowboxHasBarsOnAllFourEdges() {
		val frame = frame { x, y -> if (y < 10 || y >= 80 || x < 24 || x >= 136) BLACK else picture(x, y) }
		assertEquals(Reading.Clear(Bars(left = 24 / 160f, top = 10 / 90f, right = 24 / 160f, bottom = 10 / 90f)), look(frame))
	}

	@Test
	fun barsAFewRowsApartAreBothKept() {
		assertEquals(Reading.Clear(Bars(top = 12 / 90f, bottom = 14 / 90f)), look(letterbox(12, 14)))
	}

	@Test
	fun aSliverOfBlackAtTheEdgeIsNotABar() {
		assertEquals(Reading.Clear(Bars.None), look(letterbox(1, 1)))
	}

	@Test
	fun aBarOnOneEdgeOnlyIsADarkSkyAndNotALetterbox() {
		assertEquals(Reading.Unclear(dark = false), look(letterbox(14, 0)))
	}

	@Test
	fun barsThickerThanAnyLetterboxAreNotBelieved() {
		assertEquals(Reading.Unclear(dark = false), look(letterbox(25, 25)))
	}

	@Test
	fun specksInABarDoNotMakeItPicture() {
		// Four lit pixels in a row of 160 are within the 3% a bar may hold.
		val frame = frame { x, y ->
			when {
				y >= 12 && y < 78 -> picture(x, y)
				x % 40 == 0 -> 200
				else -> BLACK
			}
		}
		assertEquals(Reading.Clear(Bars(top = 12 / 90f, bottom = 12 / 90f)), look(frame))
	}

	@Test
	fun aCaptionInOneBarLeavesTheBarsUneven() {
		val frame = frame { x, y ->
			when {
				y in 81..84 && x in 30..130 -> 220
				y < 12 || y >= 78 -> BLACK
				else -> picture(x, y)
			}
		}
		assertEquals(Reading.Unclear(dark = false), look(frame))
	}

	@Test
	fun noiseInThePictureAndInTheBarsDoesNotMoveTheBars() {
		val noise = Random(7)
		val frame = frame { x, y ->
			when {
				y in 12..77 -> picture(x, y) + noise.nextInt(-30, 31)
				noise.nextInt(200) == 0 -> 200
				else -> BLACK + noise.nextInt(-6, 7)
			}
		}
		assertEquals(Reading.Clear(Bars(top = 12 / 90f, bottom = 12 / 90f)), look(frame))
	}

	@Test
	fun aPictureOfNothingButNoiseHasNoBars() {
		val noise = Random(11)
		assertEquals(Reading.Clear(Bars.None), look(frame { _, _ -> noise.nextInt(256) }))
	}

	@Test
	fun aFrameThatIsAllBlackSaysNothing() {
		assertEquals(Reading.Unclear(dark = true), look(frame { _, _ -> BLACK }))
	}

	@Test
	fun aFewLitWindowsInTheDarkSayNothing() {
		val frame = frame { x, y -> if (y in 40..43 && x % 40 == 5) 120 else 12 }
		assertEquals(Reading.Unclear(dark = true), look(frame))
	}

	@Test
	fun aDimSceneBetweenTwoDarkBandsIsNotALetterbox() {
		// Lit, so not blank, but with nothing bright in it: the black above and below proves nothing.
		assertEquals(Reading.Unclear(dark = true), look(frame { _, y -> if (y in 18..71) 40 else 12 }))
	}

	@Test
	fun aFadeToBlackNeverInventsBars() {
		val letterbox = Reading.Clear(Bars(top = 12 / 90f, bottom = 12 / 90f))
		for (level in 100 downTo 0 step 5) {
			val frame = frame { x, y -> if (y < 12 || y >= 78) BLACK else picture(x, y) * level / 100 }
			val reading = look(frame)
			assertTrue("at $level%: $reading", reading == letterbox || reading is Reading.Unclear)
		}
	}

	@Test
	fun aFrameNotYetDrawnIsUndrawn() {
		assertEquals(Reading.Undrawn, look(IntArray(WIDTH * HEIGHT)))
	}

	private fun letterbox(top: Int, bottom: Int) = frame { x, y -> if (y < top || y >= HEIGHT - bottom) BLACK else picture(x, y) }

	private fun pillarbox(side: Int) = frame { x, y -> if (x < side || x >= WIDTH - side) BLACK else picture(x, y) }

	private fun look(pixels: IntArray) = reader.read(pixels, WIDTH, HEIGHT)

	private fun frame(luma: (x: Int, y: Int) -> Int) = IntArray(WIDTH * HEIGHT) { i ->
		val level = luma(i % WIDTH, i / WIDTH).coerceIn(0, 255)
		OPAQUE or (level shl 16) or (level shl 8) or level
	}

	/** A bright, textured picture: no row or column of it is flat, and none is dark. */
	private fun picture(x: Int, y: Int) = 110 + (x * 7 + y * 13) % 100

	private companion object {
		const val WIDTH = 160
		const val HEIGHT = 90

		/** What black decodes to in video's limited range. */
		const val BLACK = 16
		const val OPAQUE = 0xFF shl 24
	}
}

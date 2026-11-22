package com.nphil.marquee.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRailLensTest {
	private val row = 16f
	private val glyph = 12f
	private val delta = 1e-3f

	@Test
	fun theTouchedLetterSwellsAndLeansMostAndTheLensEndsFourRowsOut() {
		assertEquals(3.6f, RailLens.scale(0f, still = false), delta)
		assertEquals(2.5f, RailLens.scale(1f, still = false), delta)
		assertEquals(1.75f, RailLens.scale(2f, still = false), delta)
		assertEquals(1.3f, RailLens.scale(3f, still = false), delta)
		assertEquals(1f, RailLens.scale(4f, still = false), delta)
		assertEquals(1f, RailLens.scale(9f, still = false), delta)
		assertEquals(46f, RailLens.lean(0f), delta)
		assertEquals(32f, RailLens.lean(1f), delta)
		assertEquals(18f, RailLens.lean(2f), delta)
		assertEquals(7f, RailLens.lean(3f), delta)
		assertEquals(0f, RailLens.lean(4f), delta)
		assertEquals(0f, RailLens.lean(9f), delta)
	}

	@Test
	fun betweenRowsTheLensBlends() {
		assertEquals(3.05f, RailLens.scale(0.5f, still = false), delta)
		assertEquals(2.125f, RailLens.scale(1.5f, still = false), delta)
		assertEquals(1.15f, RailLens.scale(3.5f, still = false), delta)
		assertEquals(39f, RailLens.lean(0.5f), delta)
		assertEquals(3.5f, RailLens.lean(3.5f), delta)
	}

	@Test
	fun lettersDimOnlyFromTheFourthRowOut() {
		for (distance in listOf(0f, 1f, 2f, 3f)) assertEquals(1f, RailLens.opacity(distance, still = false), delta)
		assertEquals(0.86f, RailLens.opacity(3.5f, still = false), delta)
		assertEquals(0.72f, RailLens.opacity(4f, still = false), delta)
		assertEquals(0.72f, RailLens.opacity(12f, still = false), delta)
	}

	@Test
	fun withAnimationsOffOnlyTheTouchedLetterGrowsAndTheRestDim() {
		assertEquals(1.7f, RailLens.scale(0.2f, still = true), delta)
		assertEquals(1f, RailLens.scale(0.5f, still = true), delta)
		assertEquals(1f, RailLens.opacity(0.2f, still = true), delta)
		assertEquals(0.72f, RailLens.opacity(1f, still = true), delta)
	}

	@Test
	fun theRowsPartFromTheFingerAndNeverCloserThanTheyAreAtRest() {
		assertEquals(0f, RailLens.position(0f, row, glyph), delta)
		assertEquals(30.352f, RailLens.position(1f, row, glyph), delta)
		assertEquals(52.712f, RailLens.position(2f, row, glyph), delta)
		assertEquals(85.888f, RailLens.position(4f, row, glyph), delta)
		var before = 0f
		for (quarter in 1..24) {
			val distance = quarter / 4f
			val at = RailLens.position(distance, row, glyph)
			assertTrue("at $distance rows", at > before && at >= distance * row - delta)
			before = at
		}
	}

	@Test
	fun pastTheLensEachRowKeepsTheRowsOwnPitch() {
		assertEquals(101.888f, RailLens.position(5f, row, glyph), delta)
		assertEquals(row, RailLens.position(6f, row, glyph) - RailLens.position(5f, row, glyph), delta)
	}

	@Test
	fun aLetterMovesAwayFromTheFingerOnItsOwnSide() {
		assertEquals(0f, RailLens.shift(0f, row, glyph), delta)
		assertEquals(14.352f, RailLens.shift(1f, row, glyph), delta)
		assertEquals(-14.352f, RailLens.shift(-1f, row, glyph), delta)
		// Past the lens the rail keeps its pitch, shifted as one by what the lens added.
		assertEquals(RailLens.shift(6f, row, glyph), RailLens.shift(9f, row, glyph), delta)
	}

	@Test
	fun theLensPanelFitsTheTouchedLetterAndTwoEitherSideAndIsNeverShorterThanAsked() {
		assertEquals(128.544f, RailLens.panelHeight(row, glyph, minimum = 96f), delta)
		assertEquals(96f, RailLens.panelHeight(row, 4f, minimum = 96f), delta)
	}
}

package com.nphil.marquee.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniBarSizeTest {
	@Test
	fun theLimitIsTheSmallestOfTheCapAndTheWindowsShares() {
		assertEquals(160f, miniBarMax(1280f, 800f), 0.01f)
		assertEquals(81f, miniBarMax(360f, 800f), 0.01f)
		assertEquals(126f, miniBarMax(800f, 360f), 0.01f)
		assertEquals(MINI_BAR_DP, miniBarMax(200f, 200f), 0f)
	}

	@Test
	fun aBarLetGoNearItsSmallestGoesBackToItAndNeverLeavesTheLimits() {
		assertEquals(MINI_BAR_DP, miniBarRest(64f, 160f), 0f)
		assertEquals(65f, miniBarRest(65f, 160f), 0f)
		assertEquals(MINI_BAR_DP, miniBarRest(20f, 160f), 0f)
		assertEquals(160f, miniBarRest(400f, 160f), 0f)
	}

	@Test
	fun thePicturesRoomIsSixteenByNineOfTheBarRoundedUp() {
		assertEquals(100f, miniThumbWidth(MINI_BAR_DP), 0f)
		assertEquals(178f, miniThumbWidth(100f), 0f)
	}

	@Test
	fun aDragMovesTheBarFromWhereItWasAndThePagesWaitForItToLand() {
		val chosen = mutableListOf<Float>()
		val size = MiniBarSize(initial = 70f) { chosen += it }
		size.fit(1280f, 800f)

		size.dragStarted()
		size.dragBy(40f)
		assertEquals(110f, size.height, 0f)
		assertEquals(70f, size.room.value, 0f)
		assertTrue(size.dragging)
		size.dragBy(500f)
		assertEquals(160f, size.height, 0f)
		size.dragBy(20f)
		size.dragEnded()

		assertFalse(size.dragging)
		assertEquals(90f, size.height, 0f)
		assertEquals(90f, size.room.value, 0f)
		assertEquals(listOf(90f), chosen)
	}

	@Test
	fun aSmallerWindowHoldsABarMadeInALargerOneToItsLimit() {
		val size = MiniBarSize(initial = 150f)
		size.fit(1280f, 800f)
		assertEquals(150f, size.height, 0f)

		size.fit(360f, 800f)
		assertEquals(81f, size.height, 0.01f)
		assertEquals(81f, size.room.value, 0.01f)
	}

	@Test
	fun aSavedHeightComesBackUnlessTheBarIsBeingDragged() {
		val size = MiniBarSize()
		size.fit(1280f, 800f)
		size.restore(100f)
		assertEquals(100f, size.height, 0f)

		size.dragStarted()
		size.restore(70f)
		assertEquals(100f, size.room.value, 0f)
	}

	@Test
	fun anAssistiveServiceSetsTheHeightInOneStepAndItIsRemembered() {
		val chosen = mutableListOf<Float>()
		val size = MiniBarSize { chosen += it }
		size.fit(1280f, 800f)
		size.adjustTo(120f)

		assertEquals(120f, size.room.value, 0f)
		assertEquals(listOf(120f), chosen)
	}
}

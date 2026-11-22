package com.nphil.marquee.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class BarScrollTest {
	@Test
	fun scrollingDownHidesTheBarUpToItsHeight() {
		assertEquals(30f, barHidden(0f, -30f, 80f), 0f)
		assertEquals(80f, barHidden(60f, -50f, 80f), 0f)
	}

	@Test
	fun scrollingUpBringsTheBarBackUpToShown() {
		assertEquals(20f, barHidden(50f, 30f, 80f), 0f)
		assertEquals(0f, barHidden(10f, 40f, 80f), 0f)
	}

	@Test
	fun aPageThatCannotScrollLeavesTheBarAlone() {
		assertEquals(0f, barHidden(0f, 0f, 80f), 0f)
		assertEquals(25f, barHidden(25f, 0f, 80f), 0f)
	}

	@Test
	fun anUnmeasuredBarStaysShown() {
		assertEquals(0f, barHidden(0f, -30f, 0f), 0f)
	}

	@Test
	fun aHalfHiddenBarRestsAtTheNearerEnd() {
		assertEquals(80f, barSettled(50f, 80f), 0f)
		assertEquals(0f, barSettled(40f, 80f), 0f)
		assertEquals(0f, barSettled(10f, 0f), 0f)
	}
}

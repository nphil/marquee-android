package com.nphil.marquee.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
	@Test
	fun showsMinutesAndSecondsUnderAnHour() {
		assertEquals("0:00", clock(0))
		assertEquals("1:05", clock(65_900))
		assertEquals("59:59", clock(3_599_000))
	}

	@Test
	fun addsHoursFromAnHourUp() {
		assertEquals("1:00:00", clock(3_600_000))
		assertEquals("2:03:04", clock(7_384_000))
	}

	@Test
	fun neverShowsLessThanZero() {
		assertEquals("0:00", clock(-5_000))
	}
}

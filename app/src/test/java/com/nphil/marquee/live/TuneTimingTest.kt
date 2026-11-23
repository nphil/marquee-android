package com.nphil.marquee.live

import com.nphil.marquee.live.TuneTiming.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneTimingTest {
	@Test
	fun `stamps are milliseconds since the tap, first one wins, in stage order`() {
		var now = 1_000L
		val timing = TuneTiming { now }
		now = 1_040
		assertTrue(timing.mark(Stage.Route))
		now = 1_900
		assertTrue(timing.mark(Stage.FirstBytes))
		now = 2_000
		assertFalse(timing.mark(Stage.FirstBytes))
		assertTrue(timing.mark(Stage.Request))
		assertTrue(timing.has(Stage.Request))
		assertFalse(timing.has(Stage.Rendered))
		assertEquals(
			listOf("route_ms" to 40L, "request_ms" to 1_000L, "first_bytes_ms" to 900L),
			timing.fields().toList(),
		)
	}
}

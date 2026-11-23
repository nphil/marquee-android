package com.nphil.marquee.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollStatsTest {
	private val slot = 1.0 / 120

	/** Six even 8 ms frames while a finger drags, then a fling of two 8 ms frames, one 16 ms and one 52 ms. */
	private fun gesture() = FrameLog().apply {
		repeat(6) { add(0.008, slot, 0) }
		add(0.008, slot, 1)
		add(0.008, slot, 1)
		add(0.016, slot, 1)
		add(0.052, slot, 1)
	}

	@Test
	fun theWholeGestureAddsUpTheWayTheIosLineDoes() {
		val all = gesture().summary(maxHz = 120)!!
		assertEquals(0.13, all.seconds, 1e-9)
		assertEquals(75.8, all.fps, 1e-9)
		assertEquals(120, all.targetHz)
		assertEquals(13.2, all.avgMs, 1e-9)
		assertEquals(52.0, all.p95Ms, 1e-9)
		assertEquals(52.0, all.maxMs, 1e-9)
		assertEquals(20.0, all.hitchPct, 1e-9)
		assertEquals(1, all.severe)
		assertEquals(6, all.missed)
		assertEquals(99.6, all.judderPct, 1e-9)
		assertEquals("8/0/1/0/1", all.gaps)
	}

	@Test
	fun aPhaseSeesOnlyItsOwnFramesAndAnEmptyOneHasNoLine() {
		val log = gesture()
		val dragging = log.summary(maxHz = 120, phase = 0)!!
		assertEquals(0.0, dragging.hitchPct, 0.0)
		assertEquals("6/0/0/0/0", dragging.gaps)
		val coasting = log.summary(maxHz = 120, phase = 1)!!
		assertEquals(50.0, coasting.hitchPct, 1e-9)
		assertEquals("2/0/1/0/1", coasting.gaps)
		assertNull(log.summary(maxHz = 120, phase = 2))
	}

	@Test
	fun theFieldsKeepTheIosNamesAndASegmentHasNoReason() {
		val summary = gesture().summary(maxHz = 120)!!
		assertEquals(
			listOf(
				"surface", "phase", "reason", "sec", "fps", "target_hz", "avg_ms", "p95_ms", "p99_ms", "max_ms",
				"hitch_pct", "severe", "missed_120", "judder_pct", "gaps_10_14_20_30_over",
			),
			summary.fields("Grid", "all", "idle").map { it.first },
		)
		assertFalse(summary.fields("Grid", "interacting", null).any { it.first == "reason" })
	}

	@Test
	fun aFullLogDropsItsOldestSixth() {
		val log = FrameLog(capacity = 12)
		repeat(13) { frame -> log.add(0.008 * (frame + 1), slot, 0) }
		assertEquals(11, log.size)
		// Frames 3 to 13 are left: their mean is 8 x 0.008 s.
		assertEquals(64.0, log.summary(maxHz = 120)!!.avgMs, 1e-9)
	}
}

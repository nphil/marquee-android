package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSeekTest {
	@Test
	fun quickSkipsAddUpFromWhereTheFirstBegan() {
		val burst = SkipBurst()
		assertEquals(Skip(10, 110.0), burst.tap(10, position = 100.0, now = 0.0))
		assertEquals(Skip(20, 120.0), burst.tap(10, position = 110.0, now = 1.0))
		assertEquals(Skip(10, 110.0), burst.tap(-10, position = 120.0, now = 2.0))
	}

	@Test
	fun aPauseBetweenTapsStartsANewSkip() {
		val burst = SkipBurst()
		burst.tap(10, position = 100.0, now = 0.0)
		assertEquals(Skip(-10, 120.0), burst.tap(-10, position = 130.0, now = 2.0))
	}

	@Test
	fun aSeekStaysInTheStreamOnlyInsideIt() {
		assertTrue(staysInStream(0.0, 100.0))
		assertTrue(staysInStream(99.0, 100.0))
		assertFalse(staysInStream(99.5, 100.0))
		assertFalse(staysInStream(-1.0, 100.0))
	}

	@Test
	fun aBufferingIsAStallOnlyOnceTheVideoHasStartedAndNoSeekCausedIt() {
		assertTrue(beginsStall(started = true, counted = false, now = 10_000, seekedAt = 5_000))
		assertFalse(beginsStall(started = false, counted = false, now = 10_000, seekedAt = 0))
		assertFalse(beginsStall(started = true, counted = true, now = 10_000, seekedAt = 5_000))
		assertFalse(beginsStall(started = true, counted = false, now = 10_000, seekedAt = 9_000))
	}
}

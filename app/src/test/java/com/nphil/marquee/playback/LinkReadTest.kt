package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkReadTest {
	@Test
	fun theClockStartsAtTheFirstChunkSoTheServersWaitIsNotTheLink() {
		val read = LinkRead(maxBytes = 4 shl 20, window = 0.5)

		// The server took 2.4 s to answer, then sent 1 MiB every 0.1 s.
		assertFalse(read.received(64 * 1024, now = 2.4))
		assertFalse(read.received(1 shl 20, now = 2.5))
		assertFalse(read.received(1 shl 20, now = 2.6))

		assertEquals(2 shl 20, read.after)
		assertEquals(0.2, read.seconds, 1e-9)
	}

	@Test
	fun theReadStopsAWindowAfterTheFirstChunkOrAtTheMostBytes() {
		val timed = LinkRead(maxBytes = 4 shl 20, window = 0.5)
		assertFalse(timed.received(1000, now = 1.0))
		assertFalse(timed.received(1000, now = 1.4))
		assertTrue(timed.received(1000, now = 1.5))

		val full = LinkRead(maxBytes = 4 shl 20, window = 0.5)
		assertFalse(full.received(1 shl 20, now = 0.0))
		assertTrue(full.received(3 shl 20, now = 0.1))
	}
}

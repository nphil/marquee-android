package com.nphil.marquee.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLoadControlTest {
	@Test
	fun `direct playback starts on the first picture but waits half a second after a stall`() {
		assertTrue(directStartsPlayback(0, rebuffering = false))
		assertFalse(directStartsPlayback(499_999, rebuffering = true))
		assertTrue(directStartsPlayback(500_000, rebuffering = true))
	}
}

package com.nphil.marquee.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReconnectTest {
	private fun StreamReconnect.Step.afterMs() = (this as StreamReconnect.Step.Reconnect).afterMs

	@Test
	fun backsOffThenGivesUpAtTheBudget() {
		val reconnect = StreamReconnect()
		var now = 1_000L
		val waits = ArrayList<Long>()
		while (true) {
			val step = reconnect.failed(now)
			if (step == StreamReconnect.Step.GiveUp) break
			waits += step.afterMs()
			now += step.afterMs() + 500
		}
		// The fourth wait is cut to what is left of the budget.
		assertEquals(listOf(1000L, 2000L, 4000L, 6500L), waits)
		assertTrue(now - 1_000 < 20_000)
	}

	@Test
	fun aSlowAttemptUsesUpTheBudget() {
		val reconnect = StreamReconnect()
		reconnect.failed(0)
		assertEquals(StreamReconnect.Step.GiveUp, reconnect.failed(16_000))
	}

	@Test
	fun theLastWaitIsCutToTheBudget() {
		val reconnect = StreamReconnect()
		reconnect.failed(0)
		assertEquals(StreamReconnect.Step.Reconnect(2, 2000), reconnect.failed(13_000))
	}

	@Test
	fun playingForAWhileStartsTheCountOver() {
		val reconnect = StreamReconnect()
		reconnect.failed(0)
		reconnect.failed(2_000)
		reconnect.landed(600_000)
		reconnect.playing(610_000)
		assertEquals(2, reconnect.attempts)
		reconnect.playing(621_000)
		assertEquals(StreamReconnect.Step.Reconnect(1, 1000), reconnect.failed(500_000))
	}

	@Test
	fun aFailureBeforeSettlingKeepsTheCount() {
		val reconnect = StreamReconnect()
		reconnect.failed(0)
		reconnect.landed(600_000)
		assertEquals(StreamReconnect.Step.Reconnect(2, 2000), reconnect.failed(3_000))
	}

	@Test
	fun loadsAreRetriedAfterOneTwoFourAndEightSeconds() {
		assertEquals(listOf(1000L, 2000L, 4000L, 8000L), (1..4).map(StreamReconnect::loadRetryDelayMs))
		assertEquals(C.TIME_UNSET, StreamReconnect.loadRetryDelayMs(5))
	}

	@Test
	fun onlyRefusalsThatStayAreNotRetried() {
		for (status in listOf(401, 403, 404, 410)) assertFalse(StreamReconnect.isRetryable(status))
		for (status in listOf(null, 408, 429, 500, 502, 503)) assertTrue(StreamReconnect.isRetryable(status))
		assertTrue(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
		assertTrue(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null))
		assertTrue(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
		assertFalse(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
		assertFalse(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
		assertFalse(StreamReconnect.isRetryable(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
		assertTrue(StreamReconnect.sessionLost(404))
		assertFalse(StreamReconnect.sessionLost(503))
	}
}

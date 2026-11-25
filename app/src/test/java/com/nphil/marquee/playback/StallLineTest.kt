package com.nphil.marquee.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class StallLineTest {
	private fun reading(loadError: LoadFailure? = null) = StallReading(
		aheadMs = -40, totalMs = 1200, isLoading = true, playbackState = Player.STATE_BUFFERING, playWhenReady = true,
		suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE, loadError = loadError, estimateBps = 8_500_000, droppedFrames = 3,
	)

	@Test
	fun theLineNamesWhatTheEngineKnewAndLeavesOutWhatIsNone() {
		val first = stallLineFields(reading(LoadFailure("InvalidResponseCodeException", 503)), again = false).toMap()
		assertEquals(null, first["again"])
		assertEquals(0L, first["ahead"])
		assertEquals(1200L, first["buffered"])
		assertEquals("buffering", first["state"])
		assertEquals(null, first["suppressed"])
		assertEquals("InvalidResponseCodeException", first["loadErrType"])
		assertEquals(503, first["loadErrStatus"])
		assertEquals(8500L, first["estimateKbps"])
		assertEquals("yes", stallLineFields(reading(), again = true).toMap()["again"])
	}
}

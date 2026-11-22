package com.nphil.marquee.playback

import com.nphil.marquee.core.DolbyVision
import com.nphil.marquee.core.PlexStream
import com.nphil.marquee.core.TranscodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsTest {
	@Test
	fun aStreamIsDescribedAsPlexListsIt() {
		val hevc = PlexStream(codec = "hevc", profile = "main 10", level = 153, width = 3840, height = 2160, bitDepth = 10, colorTrc = "smpte2084")
		val avc = PlexStream(codec = "h264", profile = "high", level = 41, width = 1920, height = 800)

		assertEquals("HEVC Main 10, level 5.1", hevc.formatSummary())
		assertEquals("H264 High, level 4.1", avc.formatSummary())
		assertEquals("3840 × 2160, 10-bit", hevc.sizeSummary())
		assertEquals("1920 × 800", avc.sizeSummary())
		assertNull(PlexStream(codec = "h264").sizeSummary())
		assertEquals("HDR10", hevc.dynamicRange())
		assertEquals("SDR", avc.dynamicRange())
		assertEquals("Dolby Vision profile 8", PlexStream(dolbyVision = DolbyVision(profile = 8), colorTrc = "smpte2084").dynamicRange())
	}

	@Test
	fun theNumbersReadAsAPersonWouldSayThem() {
		assertEquals("5.1", channelWords(6))
		assertEquals("3 channels", channelWords(3))
		assertEquals("12.5 Mbps", mbps(12_500_000.0))
		assertEquals("4.21 GB", fileSize(4_210_000_000))
		assertEquals("812 MB", fileSize(812_000_000))
		assertEquals("23.976", trimmed(23.976, 3))
		assertEquals("24", trimmed(24.0, 3))
		assertEquals("waiting", startSummary(null, null))
		assertEquals("plan 0.31 s · ready 1.42 s", startSummary(310, 1420))
	}

	@Test
	fun theChipsDecodersAreHardwareAndGoogleAndFfmpegAreSoftware() {
		assertTrue(isSoftwareDecoder("c2.android.hevc.decoder"))
		assertTrue(isSoftwareDecoder("OMX.google.h264.decoder"))
		assertFalse(isSoftwareDecoder("c2.qti.hevc.decoder"))
	}

	@Test
	fun theServersConversionReadsInOneLine() {
		assertNull(TranscodeState(active = false, video = "copy").words())
		assertEquals(
			"video copy · audio transcode · subtitles none · 2.4× real time · throttled · software",
			TranscodeState(true, "copy", "transcode", "none", speed = 2.4, throttled = true).words(),
		)
		assertEquals(
			"video transcode · subtitles burn · 0.8× real time · hardware",
			TranscodeState(true, "transcode", subtitle = "burn", speed = 0.8, fullPipeline = true).words(),
		)
	}

	@Test
	fun theLastLoadsSayWhetherTheLinkKeepsAheadOfPlayback() {
		val stats = EngineStats()
		assertEquals(LoadSummary(0, 0, null), stats.loads())

		repeat(2) { stats.record(bytes = 1_000_000, loadMs = 2_000, mediaMs = 4_000) }
		assertEquals(LoadSummary(2, 4_000, 2.0), stats.loads())

		// A progressive file's chunks carry no media time: the rate is known, the pace against playback is not.
		stats.reset()
		stats.record(bytes = 500_000, loadMs = 1_000, mediaMs = 0)
		assertEquals(LoadSummary(1, 4_000, null), stats.loads())

		repeat(10) { stats.record(bytes = 1, loadMs = 1, mediaMs = 0) }
		assertEquals(6, stats.loads().count)
	}
}

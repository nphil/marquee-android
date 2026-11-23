package com.nphil.marquee.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDecodersTest {
	private val ffmpegDecodesMpeg2 = Decoders { it == "video/mpeg2" }

	@Test
	fun softwareMpeg2IsWantedOnlyWhenTheCodecListHasNoMpeg2Decoder() {
		val withoutOne = DeviceDecoders(setOf("video/avc", "video/hevc"), ffmpegDecodesMpeg2)
		assertTrue(withoutOne.platformLacks("video/mpeg2"))
		assertTrue(withoutOne.canDecode("video/mpeg2"))

		val withOne = DeviceDecoders(setOf("video/avc", "video/mpeg2"), ffmpegDecodesMpeg2)
		assertFalse(withOne.platformLacks("video/mpeg2"))
		assertTrue(withOne.canDecode("video/mpeg2"))
	}

	@Test
	fun aTabletWithNoMpeg2DecoderAndNoLibrariesCannotDecodeIt() {
		val decoders = DeviceDecoders(setOf("video/avc"), Decoders { false })
		assertTrue(decoders.platformLacks("video/mpeg2"))
		assertFalse(decoders.canDecode("video/mpeg2"))
	}

	@Test
	fun theCodecListIsMatchedWithoutRegardForCase() {
		val decoders = DeviceDecoders(setOf("video/mpeg2"), Decoders { false })
		assertFalse(decoders.platformLacks("Video/MPEG2"))
		assertTrue(decoders.canDecode("Video/MPEG2"))
	}
}

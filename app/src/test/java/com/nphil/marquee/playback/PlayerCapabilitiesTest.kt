package com.nphil.marquee.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCapabilitiesTest {
	@Test
	fun audioTheTabletCannotDecodeIsLeftOutSoTheServerConvertsIt() {
		val codecs = PlayerCapabilities.audioCodecs(Decoders { it == "audio/ac3" || it == "audio/eac3" })
		assertTrue("ac3" in codecs && "eac3" in codecs)
		assertFalse("dca" in codecs || "dts" in codecs || "truehd" in codecs)
	}

	@Test
	fun aacAndPcmNeedNoDecoder() {
		val codecs = PlayerCapabilities.audioCodecs(Decoders { false })
		assertTrue("aac" in codecs && "pcm_s16le" in codecs)
		assertFalse("ac3" in codecs)
	}

	@Test
	fun aDtsDecoderPlaysBothNamesPlexUsesForIt() {
		val codecs = PlayerCapabilities.audioCodecs(Decoders { it == "audio/vnd.dts" })
		assertTrue("dca" in codecs && "dts" in codecs)
	}
}

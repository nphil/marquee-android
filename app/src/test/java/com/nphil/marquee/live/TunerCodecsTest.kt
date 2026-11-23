package com.nphil.marquee.live

import com.nphil.marquee.playback.Decoders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunerCodecsTest {
	private val withoutMpeg2 = Decoders { it != "video/mpeg2" }

	@Test
	fun aLumaChannelNeedsTheBroadcastsMpeg2PictureAndAc3Sound() {
		val codecs = tunerCodecs(entry(), isLuma = true)
		assertEquals("video/mpeg2", codecs.video)
		assertEquals("audio/ac3", codecs.audio)
		assertEquals(listOf("video/mpeg2"), codecs.missing(withoutMpeg2))
		assertEquals(emptyList<String>(), codecs.missing { true })
	}

	@Test
	fun aLineupThatNamesItsCodecsIsBelievedOverTheAtscDefault() {
		val codecs = tunerCodecs(entry("H264", "AAC"), isLuma = true)
		assertEquals("video/avc", codecs.video)
		assertEquals("audio/mp4a-latm", codecs.audio)
		assertEquals(emptyList<String>(), codecs.missing(withoutMpeg2))
	}

	@Test
	fun everyCodecNameThePhoneDecodesMapsToItsMimeType() {
		val pictures = listOf("MPEG2" to "video/mpeg2", "h264" to "video/avc", "HEVC" to "video/hevc")
		assertEquals(pictures.map { it.second }, pictures.map { tunerCodecs(entry(video = it.first), false).video })
		val sounds = listOf("AC3" to "audio/ac3", "EAC3" to "audio/eac3", "AAC" to "audio/mp4a-latm", "MPEG" to "audio/mpeg-L2")
		assertEquals(sounds.map { it.second }, sounds.map { tunerCodecs(entry(audio = it.first), false).audio })
	}

	@Test
	fun aTunerThatNamesNothingAndIsNotALumaIsNotSecondGuessed() {
		val codecs = tunerCodecs(entry(), isLuma = false)
		assertNull(codecs.video)
		assertNull(codecs.audio)
		assertEquals(emptyList<String>(), codecs.missing { false })
		assertEquals(emptyList<String>(), tunerCodecs(null, isLuma = false).missing { false })
		// Core already sends a codec the phone cannot name (VP9) to Plex.
		assertNull(tunerCodecs(entry(video = "VP9"), isLuma = true).video)
	}

	private fun entry(video: String? = null, audio: String? = null): JsonObject = buildJsonObject {
		put("GuideNumber", "2.1")
		video?.let { put("VideoCodec", it) }
		audio?.let { put("AudioCodec", it) }
	}
}

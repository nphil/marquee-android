package com.nphil.marquee.playback

import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPlayTest {
	private val none = Decoders { false }

	private fun media(
		video: String?,
		audio: String?,
		container: String,
		key: String = "/library/parts/1/file.$container",
	) = PlexMedia(videoCodec = video, audioCodec = audio, container = container, parts = listOf(PlexPart(key = key)))

	@Test
	fun h264AacInMp4PlaysOnAnyTablet() {
		val part = DirectPlay.part(listOf(media("h264", "aac", "mp4")), none)
		assertEquals("/library/parts/1/file.mp4", part?.key)
	}

	@Test
	fun matroskaIsReadAsItIs() {
		assertEquals("/library/parts/1/file.mkv", DirectPlay.part(listOf(media("h264", "aac", "mkv")), none)?.key)
	}

	@Test
	fun hevcNeedsADecoder() {
		val hevc = listOf(media("hevc", "aac", "mp4"))
		assertNull(DirectPlay.part(hevc, none))
		assertEquals("/library/parts/1/file.mp4", DirectPlay.part(hevc) { it == "video/hevc" }?.key)
	}

	@Test
	fun aVersionItCannotPlayIsPassedOverForTheNext() {
		val dts = media("h264", "dts", "mkv", key = "/library/parts/1/dts.mkv")
		val aac = media("h264", "aac", "mp4", key = "/library/parts/2/aac.mp4")
		assertEquals("/library/parts/2/aac.mp4", DirectPlay.part(listOf(dts, aac), none)?.key)
	}

	@Test
	fun aContainerMedia3DoesNotReadIsRejected() {
		assertNull(DirectPlay.part(listOf(media("h264", "aac", "avi")), none))
	}

	@Test
	fun anUnknownCodecIsRejectedAndAnUnreportedOneIsNot() {
		assertNull(DirectPlay.part(listOf(media("wmv3", "aac", "mp4")), none))
		assertEquals("/library/parts/1/file.mp4", DirectPlay.part(listOf(media(null, null, "mp4")), none)?.key)
	}

	@Test
	fun nothingOrAPartWithoutAKeyIsNull() {
		assertNull(DirectPlay.part(emptyList(), none))
		assertNull(DirectPlay.part(listOf(media("h264", "aac", "mp4", key = "")), none))
	}
}

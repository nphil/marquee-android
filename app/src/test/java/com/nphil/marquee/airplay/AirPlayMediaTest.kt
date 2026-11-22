package com.nphil.marquee.airplay

import com.nphil.marquee.core.PlayStream
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayMediaTest {
	@Test fun discoveryRequiresVideoV2AndNormalAccess() {
		val lg = mapOf("features" to "0x7F8AD0,0x38BCB46", "deviceid" to "02:00:00:00:00:01")
		fun discovered(properties: Map<String, String>) = Receiver("Display", "192.0.2.10", 7000, properties)
		assertTrue(acceptsAirPlayVideo(discovered(lg + ("flags" to "0x8"))))
		assertTrue(acceptsAirPlayVideo(discovered(lg + ("pw" to "true"))))
		assertFalse(acceptsAirPlayVideo(discovered(lg + ("act" to "2"))))
		assertFalse(acceptsAirPlayVideo(discovered(lg + ("acl" to "1"))))
		assertFalse(acceptsAirPlayVideo(discovered(mapOf("features" to "0x1"))))
		assertFalse(acceptsAirPlayVideo(discovered(mapOf("features" to "0x445F8A00,0x1C340"))))
	}

	@Test fun onlyTheBridgeMp4UrlMayReachTheReceiver() {
		val url = "https://example.test/library/parts/9/file.mp4?X-Plex-Token=delegation"
		val file = PlayStream(PlayStream.StreamKind.File, url)
		assertEquals(url, airPlayFileUrl(file, "video/mp4"))
		assertNull(airPlayFileUrl(file.copy(kind = PlayStream.StreamKind.Hls), "video/mp4"))
		assertNull(airPlayFileUrl(file.copy(headers = mapOf("X-Plex-Token" to "account")), "video/mp4"))
		assertNull(airPlayFileUrl(file.copy(url = url.replace("https://", "https://account:secret@")), "video/mp4"))
		assertNull(airPlayFileUrl(file.copy(url = "$url&X-Plex-Token=account"), "video/mp4"))
		assertNull(airPlayFileUrl(file.copy(url = url.replace("/library/parts/", "/library/metadata/")), "video/mp4"))
	}

	@Test fun handoffDoesNotTreatBufferingAsPlaybackOrPreloadStopAsAnEnd() {
		assertEquals("buffering", airPlayState(PlaybackState.Loading, 0.0, ready = false))
		assertEquals("buffering", airPlayState(PlaybackState.Playing, 0.0, ready = false))
		assertEquals("playing", airPlayState(PlaybackState.Playing, 1.0, ready = false))
		assertEquals("paused", airPlayState(PlaybackState.Paused, 0.0, ready = false))
		assertNull(airPlayState(PlaybackState.Stopped, 0.0, ready = false))
		assertEquals("cancelled", airPlayState(PlaybackState.Stopped, 0.0, ready = true))
	}

	@Test fun pairingUsesTheExactReceiverSecret() {
		assertTrue(validAirPlaySecret("0039", password = false))
		assertFalse(validAirPlaySecret("39", password = false))
		assertFalse(validAirPlaySecret(" 039", password = false))
		assertTrue(validAirPlaySecret("a password ", password = true))
		assertFalse(validAirPlaySecret("", password = true))
	}
}

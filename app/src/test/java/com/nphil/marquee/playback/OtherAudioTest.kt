package com.nphil.marquee.playback

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherAudioTest {
	@Test
	fun onlyPlayersThatCanBeHeardAreCounted() {
		val usages = listOf(
			AudioAttributes.USAGE_MEDIA,
			AudioAttributes.USAGE_GAME,
			AudioAttributes.USAGE_NOTIFICATION,
			AudioAttributes.USAGE_ALARM,
		)
		assertEquals(2, OtherAudio.heardPlayers(usages))
		assertEquals(0, OtherAudio.heardPlayers(emptyList()))
	}

	@Test
	fun theAppsOwnSoundIsNotAnotherAppsAudio() {
		assertFalse(OtherAudio.playing(heardPlayers = 1, own = 1))
		assertTrue(OtherAudio.playing(heardPlayers = 2, own = 1))
		assertFalse(OtherAudio.playing(heardPlayers = 0, own = 0))
		assertTrue(OtherAudio.playing(heardPlayers = 1, own = 0))
	}
}

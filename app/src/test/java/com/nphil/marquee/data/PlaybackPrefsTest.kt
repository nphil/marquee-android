package com.nphil.marquee.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPrefsTest {
	@Test
	fun aProfileWithNothingSavedGetsTheDefaults() {
		val prefs = mutablePreferencesOf().playbackPrefs("a")
		assertEquals(PlaybackPrefs(playsTrailers = true, playsThemeMusic = true, themeMusicVolume = 0.5f), prefs)
		assertEquals(PlaybackPrefs(), mutablePreferencesOf().playbackPrefs(null))
	}

	@Test
	fun eachProfileKeepsItsOwnChoices() {
		val stored = mutablePreferencesOf()
		stored.setPlaybackPrefs("a", PlaybackPrefs(playsTrailers = false, playsThemeMusic = false, themeMusicVolume = 0.2f))
		assertEquals(PlaybackPrefs(false, false, 0.2f), stored.playbackPrefs("a"))
		assertEquals(PlaybackPrefs(), stored.playbackPrefs("b"))
	}

	@Test
	fun aVolumeOutsideZeroToOneIsClamped() {
		val stored = mutablePreferencesOf()
		stored.setPlaybackPrefs("a", PlaybackPrefs(themeMusicVolume = 4f))
		assertEquals(1f, stored.playbackPrefs("a").themeMusicVolume, 0f)
	}
}

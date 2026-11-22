package com.nphil.marquee.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nphil.marquee.core.CreditsAutoplay
import com.nphil.marquee.core.SkipTapMode
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

	@Test
	fun theSkipAndAutoplayChoicesAreKeptAndAValueOutOfRangeFallsBack() {
		val stored = mutablePreferencesOf()
		val chosen = PlaybackPrefs(
			skipInterval = 30, skipTaps = SkipTapMode.Chained, introSkipDelay = 0,
			autoplaysNext = false, creditsAutoplay = CreditsAutoplay.SkipInstantly, nextEpisodeDelay = 15, keepsPlaying = false,
		)
		stored.setPlaybackPrefs("a", chosen)
		assertEquals(chosen, stored.playbackPrefs("a"))

		stored.setPlaybackPrefs("a", PlaybackPrefs(skipInterval = 7, introSkipDelay = 4, nextEpisodeDelay = 0))
		assertEquals(PlaybackPrefs(), stored.playbackPrefs("a"))
	}

	@Test
	fun directTunerIsOnByDefaultAndCanBeDisabledPerProfile() {
		val stored = mutablePreferencesOf()
		stored.setPlaybackPrefs("a", PlaybackPrefs(readsTunerDirectly = false))
		assertEquals(false, stored.playbackPrefs("a").readsTunerDirectly)
		assertEquals(true, stored.playbackPrefs("b").readsTunerDirectly)
	}
}

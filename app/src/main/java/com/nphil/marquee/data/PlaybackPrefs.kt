package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nphil.marquee.core.CreditsAutoplay
import com.nphil.marquee.core.DirectPlayMode
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.core.SkipTapMode
import com.nphil.marquee.playback.ThemeFade

/** A Home profile's choices for the hero trailer, a show's theme music and how titles play (Settings › Video & Playback). */
@Immutable
data class PlaybackPrefs(
	val playsTrailers: Boolean = true,
	val playsThemeMusic: Boolean = true,
	/** 0 to 1. */
	val themeMusicVolume: Float = ThemeFade.DEFAULT_LEVEL,
	/** What a title streams at when the server converts it: [QualityPreset.AUTO], [QualityPreset.ORIGINAL] or a ceiling in kbit/s. */
	val quality: Int = QualityPreset.AUTO,
	/** Holds a metered network to a low bitrate. */
	val savesData: Boolean = false,
	val directPlay: DirectPlayMode = DirectPlayMode.Auto,
	/** Seconds the skip buttons and a double-tap jump: one of [SKIP_INTERVALS]. */
	val skipInterval: Int = 10,
	val skipTaps: SkipTapMode = SkipTapMode.DoubleTap,
	/** Seconds before Skip Intro takes itself; 0 leaves it to a tap. */
	val introSkipDelay: Int = 5,
	/** The next episode follows when one ends. */
	val autoplaysNext: Boolean = true,
	val creditsAutoplay: CreditsAutoplay = CreditsAutoplay.Countdown,
	/** Seconds of the Up Next countdown: one of [COUNTDOWNS]. */
	val nextEpisodeDelay: Int = 5,
	/** The sound carries on when the player leaves the screen, held by a notification with lock-screen controls. */
	val keepsPlaying: Boolean = true,
	val readsTunerDirectly: Boolean = true,
	/** Interlaced channels play the original broadcast (about 60 pictures a second) instead of the tuner's 30. */
	val prefersSmoothMotion: Boolean = true,
) {
	companion object {
		val SKIP_INTERVALS = listOf(5, 10, 15, 30, 60)

		/** Seconds for the intro and Up Next countdowns. */
		val COUNTDOWNS = listOf(3, 5, 10, 15, 30)
	}
}

// Keyed by the profile's uuid, so each person in the Home keeps their own.
private fun trailers(profile: String) = booleanPreferencesKey("playback.$profile.trailers")
private fun themeMusic(profile: String) = booleanPreferencesKey("playback.$profile.themeMusic")
private fun themeVolume(profile: String) = floatPreferencesKey("playback.$profile.themeMusicVolume")
private fun quality(profile: String) = intPreferencesKey("playback.$profile.quality")
private fun savesData(profile: String) = booleanPreferencesKey("playback.$profile.savesData")
private fun directPlay(profile: String) = stringPreferencesKey("playback.$profile.directPlay")
private fun skipInterval(profile: String) = intPreferencesKey("playback.$profile.skipInterval")
private fun skipTaps(profile: String) = stringPreferencesKey("playback.$profile.skipTaps")
private fun introSkipDelay(profile: String) = intPreferencesKey("playback.$profile.introSkipDelay")
private fun autoplaysNext(profile: String) = booleanPreferencesKey("playback.$profile.autoplaysNext")
private fun creditsAutoplay(profile: String) = stringPreferencesKey("playback.$profile.creditsAutoplay")
private fun nextEpisodeDelay(profile: String) = intPreferencesKey("playback.$profile.nextEpisodeDelay")
private fun keepsPlaying(profile: String) = booleanPreferencesKey("playback.$profile.keepsPlaying")
private fun readsTunerDirectly(profile: String) = booleanPreferencesKey("playback.$profile.readsTunerDirectly")
private fun prefersSmoothMotion(profile: String) = booleanPreferencesKey("playback.$profile.prefersSmoothMotion")

internal fun Preferences.playbackPrefs(profile: String?): PlaybackPrefs {
	if (profile == null) return PlaybackPrefs()
	val defaults = PlaybackPrefs()
	return PlaybackPrefs(
		playsTrailers = this[trailers(profile)] ?: defaults.playsTrailers,
		playsThemeMusic = this[themeMusic(profile)] ?: defaults.playsThemeMusic,
		themeMusicVolume = (this[themeVolume(profile)] ?: defaults.themeMusicVolume).coerceIn(0f, 1f),
		quality = this[quality(profile)]?.takeIf { it in QualityPreset.all } ?: defaults.quality,
		savesData = this[savesData(profile)] ?: defaults.savesData,
		directPlay = DirectPlayMode.entries.firstOrNull { it.name == this[directPlay(profile)] } ?: defaults.directPlay,
		skipInterval = this[skipInterval(profile)]?.takeIf { it in PlaybackPrefs.SKIP_INTERVALS } ?: defaults.skipInterval,
		skipTaps = SkipTapMode.entries.firstOrNull { it.name == this[skipTaps(profile)] } ?: defaults.skipTaps,
		introSkipDelay = this[introSkipDelay(profile)]?.takeIf { it == 0 || it in PlaybackPrefs.COUNTDOWNS } ?: defaults.introSkipDelay,
		autoplaysNext = this[autoplaysNext(profile)] ?: defaults.autoplaysNext,
		creditsAutoplay = CreditsAutoplay.entries.firstOrNull { it.name == this[creditsAutoplay(profile)] } ?: defaults.creditsAutoplay,
		nextEpisodeDelay = this[nextEpisodeDelay(profile)]?.takeIf { it in PlaybackPrefs.COUNTDOWNS } ?: defaults.nextEpisodeDelay,
		keepsPlaying = this[keepsPlaying(profile)] ?: defaults.keepsPlaying,
		readsTunerDirectly = this[readsTunerDirectly(profile)] ?: defaults.readsTunerDirectly,
		prefersSmoothMotion = this[prefersSmoothMotion(profile)] ?: defaults.prefersSmoothMotion,
	)
}

internal fun MutablePreferences.setPlaybackPrefs(profile: String, prefs: PlaybackPrefs) {
	this[trailers(profile)] = prefs.playsTrailers
	this[themeMusic(profile)] = prefs.playsThemeMusic
	this[themeVolume(profile)] = prefs.themeMusicVolume.coerceIn(0f, 1f)
	this[quality(profile)] = prefs.quality
	this[savesData(profile)] = prefs.savesData
	this[directPlay(profile)] = prefs.directPlay.name
	this[skipInterval(profile)] = prefs.skipInterval
	this[skipTaps(profile)] = prefs.skipTaps.name
	this[introSkipDelay(profile)] = prefs.introSkipDelay
	this[autoplaysNext(profile)] = prefs.autoplaysNext
	this[creditsAutoplay(profile)] = prefs.creditsAutoplay.name
	this[nextEpisodeDelay(profile)] = prefs.nextEpisodeDelay
	this[keepsPlaying(profile)] = prefs.keepsPlaying
	this[readsTunerDirectly(profile)] = prefs.readsTunerDirectly
	this[prefersSmoothMotion(profile)] = prefs.prefersSmoothMotion
}

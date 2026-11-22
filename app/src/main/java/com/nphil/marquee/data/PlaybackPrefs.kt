package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.nphil.marquee.playback.ThemeFade

/** A Home profile's choices for the hero trailer and a show's theme music (Settings › Video & Playback). */
@Immutable
data class PlaybackPrefs(
	val playsTrailers: Boolean = true,
	val playsThemeMusic: Boolean = true,
	/** 0 to 1. */
	val themeMusicVolume: Float = ThemeFade.DEFAULT_LEVEL,
)

// Keyed by the profile's uuid, so each person in the Home keeps their own.
private fun trailers(profile: String) = booleanPreferencesKey("playback.$profile.trailers")
private fun themeMusic(profile: String) = booleanPreferencesKey("playback.$profile.themeMusic")
private fun themeVolume(profile: String) = floatPreferencesKey("playback.$profile.themeMusicVolume")

internal fun Preferences.playbackPrefs(profile: String?): PlaybackPrefs {
	if (profile == null) return PlaybackPrefs()
	val defaults = PlaybackPrefs()
	return PlaybackPrefs(
		playsTrailers = this[trailers(profile)] ?: defaults.playsTrailers,
		playsThemeMusic = this[themeMusic(profile)] ?: defaults.playsThemeMusic,
		themeMusicVolume = (this[themeVolume(profile)] ?: defaults.themeMusicVolume).coerceIn(0f, 1f),
	)
}

internal fun MutablePreferences.setPlaybackPrefs(profile: String, prefs: PlaybackPrefs) {
	this[trailers(profile)] = prefs.playsTrailers
	this[themeMusic(profile)] = prefs.playsThemeMusic
	this[themeVolume(profile)] = prefs.themeMusicVolume.coerceIn(0f, 1f)
}

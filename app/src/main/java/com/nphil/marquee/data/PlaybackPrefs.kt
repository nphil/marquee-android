package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nphil.marquee.core.DirectPlayMode
import com.nphil.marquee.core.QualityPreset
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
)

// Keyed by the profile's uuid, so each person in the Home keeps their own.
private fun trailers(profile: String) = booleanPreferencesKey("playback.$profile.trailers")
private fun themeMusic(profile: String) = booleanPreferencesKey("playback.$profile.themeMusic")
private fun themeVolume(profile: String) = floatPreferencesKey("playback.$profile.themeMusicVolume")
private fun quality(profile: String) = intPreferencesKey("playback.$profile.quality")
private fun savesData(profile: String) = booleanPreferencesKey("playback.$profile.savesData")
private fun directPlay(profile: String) = stringPreferencesKey("playback.$profile.directPlay")

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
	)
}

internal fun MutablePreferences.setPlaybackPrefs(profile: String, prefs: PlaybackPrefs) {
	this[trailers(profile)] = prefs.playsTrailers
	this[themeMusic(profile)] = prefs.playsThemeMusic
	this[themeVolume(profile)] = prefs.themeMusicVolume.coerceIn(0f, 1f)
	this[quality(profile)] = prefs.quality
	this[savesData(profile)] = prefs.savesData
	this[directPlay(profile)] = prefs.directPlay.name
}

package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nphil.marquee.bigscreen.BigScreenUse
import com.nphil.marquee.bigscreen.DisplayKind
import com.nphil.marquee.bigscreen.DisplayMemory
import com.nphil.marquee.core.CoreJson

/**
 * What Settings › Big Screen keeps. A device setting, not one of the Plex profile: whether a display is a pair of
 * glasses is a fact about what is plugged in. Off until the owner turns it on.
 */
@Immutable
data class BigScreenPrefs(
	val enabled: Boolean = false,
	/** What a display with no remembered answer is used for. */
	val use: BigScreenUse = BigScreenUse.Ask,
	/** What a display that was not told apart yet is taken for. */
	val kind: DisplayKind = DisplayKind.Glasses,
	val tilt: Boolean = true,
	val displays: DisplayMemory = DisplayMemory(),
	/** The first time the remote was put away, the phone says where it comes back from; said once. */
	val leftRemote: Boolean = false,
)

private val ENABLED = booleanPreferencesKey("marquee.bigscreen.enabled")
private val USE = stringPreferencesKey("marquee.bigscreen.use")
private val KIND = stringPreferencesKey("marquee.bigscreen.kind")
private val TILT = booleanPreferencesKey("marquee.bigscreen.tilt")
private val DISPLAYS = stringPreferencesKey("marquee.bigscreen.displays")
private val LEFT_REMOTE = booleanPreferencesKey("marquee.bigscreen.leftRemote")

internal fun Preferences.bigScreenPrefs() = BigScreenPrefs(
	enabled = this[ENABLED] ?: false,
	use = this[USE]?.let { name -> BigScreenUse.entries.firstOrNull { it.wire == name } } ?: BigScreenUse.Ask,
	kind = this[KIND]?.let { name -> DisplayKind.entries.firstOrNull { it.wire == name } } ?: DisplayKind.Glasses,
	tilt = this[TILT] ?: true,
	displays = this[DISPLAYS]?.let { runCatching { CoreJson.decodeFromString(DisplayMemory.serializer(), it) }.getOrNull() } ?: DisplayMemory(),
	leftRemote = this[LEFT_REMOTE] ?: false,
)

internal fun MutablePreferences.setBigScreenPrefs(prefs: BigScreenPrefs) {
	this[ENABLED] = prefs.enabled
	this[USE] = prefs.use.wire
	this[KIND] = prefs.kind.wire
	this[TILT] = prefs.tilt
	if (prefs.displays.displays.isEmpty()) remove(DISPLAYS) else this[DISPLAYS] = CoreJson.encodeToString(DisplayMemory.serializer(), prefs.displays)
	this[LEFT_REMOTE] = prefs.leftRemote
}

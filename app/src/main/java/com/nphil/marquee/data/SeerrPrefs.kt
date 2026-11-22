package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * What Settings › Integrations › Seerr keeps that is not secret. The API key and the kept sign-in cookie are
 * secrets and live in the "seerr" [SecretStore], which a Plex sign-out leaves alone.
 */
@Immutable
data class SeerrPrefs(
	/** Seerr's address, as Core normalised it; empty until one is saved. */
	val address: String = "",
	/** Whether Requests is shown. Off until the owner turns it on, whatever else is set. */
	val enabled: Boolean = false,
	/** Pulsarr requests whatever goes on a Watchlist, so a title page says adding it there requests it too. */
	val pulsarr: Boolean = false,
)

private val ADDRESS = stringPreferencesKey("marquee.seerr.address")
private val ENABLED = booleanPreferencesKey("marquee.seerr.enabled")
private val PULSARR = booleanPreferencesKey("marquee.seerr.pulsarr")

internal fun Preferences.seerrPrefs() = SeerrPrefs(
	address = this[ADDRESS].orEmpty(),
	enabled = this[ENABLED] ?: false,
	pulsarr = this[PULSARR] ?: false,
)

internal fun MutablePreferences.setSeerrPrefs(prefs: SeerrPrefs) {
	if (prefs.address.isEmpty()) remove(ADDRESS) else this[ADDRESS] = prefs.address
	this[ENABLED] = prefs.enabled
	this[PULSARR] = prefs.pulsarr
}

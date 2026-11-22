package com.nphil.marquee.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.HomeLayout
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** What the app keeps that is not secret: themes, the chosen server and profile, server addresses and how browsing is set up. */
class AppSettings(context: Context) : BrowsePreferences {
	private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

	val themeChoice: Flow<ThemeChoice> = store.data.map { it.themeChoice() }.distinctUntilChanged()

	suspend fun updateTheme(change: (ThemeChoice) -> ThemeChoice) {
		store.edit { prefs ->
			val next = change(prefs.themeChoice())
			prefs[THEME_MATCH] = next.matchSystem
			prefs[THEME_LIGHT] = next.lightId
			prefs[THEME_DARK] = next.darkId
			prefs[THEME_FIXED] = next.fixedMode.name
		}
	}

	/** The trailer and theme music choices of a Home profile; defaults for one that never chose, or no profile. */
	fun playback(profile: String?): Flow<PlaybackPrefs> = store.data.map { it.playbackPrefs(profile) }.distinctUntilChanged()

	suspend fun updatePlayback(profile: String, change: (PlaybackPrefs) -> PlaybackPrefs) {
		store.edit { prefs -> prefs.setPlaybackPrefs(profile, change(prefs.playbackPrefs(profile))) }
	}

	/** Seerr's address and switches; see [SeerrPrefs]. */
	val seerr: Flow<SeerrPrefs> = store.data.map { it.seerrPrefs() }.distinctUntilChanged()

	suspend fun updateSeerr(change: (SeerrPrefs) -> SeerrPrefs) {
		store.edit { prefs -> prefs.setSeerrPrefs(change(prefs.seerrPrefs())) }
	}

	/** This install's X-Plex-Client-Identifier, made on first use and kept across sign-ins. */
	suspend fun clientIdentifier(): String {
		store.data.first()[CLIENT_ID]?.let { return it }
		val prefs = store.edit { prefs -> if (prefs[CLIENT_ID] == null) prefs[CLIENT_ID] = UUID.randomUUID().toString() }
		return checkNotNull(prefs[CLIENT_ID])
	}

	suspend fun account(): StoredAccount {
		val prefs = store.data.first()
		return StoredAccount(
			serverId = prefs[SERVER_ID],
			profile = prefs[PROFILE]?.let { runCatching { CoreJson.decodeFromString(PlexHomeUser.serializer(), it) }.getOrNull() },
			addresses = prefs.addresses(),
		)
	}

	suspend fun setServerId(id: String) {
		store.edit { it[SERVER_ID] = id }
	}

	suspend fun setProfile(profile: PlexHomeUser?) {
		store.edit { prefs ->
			if (profile == null) {
				prefs.remove(PROFILE)
			} else {
				prefs[PROFILE] = CoreJson.encodeToString(PlexHomeUser.serializer(), profile)
			}
		}
	}

	/** Keeps the address that answered for a server, tried first next time. */
	suspend fun setAddress(serverId: String, uri: String) {
		store.edit { prefs ->
			val addresses = prefs.addresses()
			if (addresses[serverId] != uri) prefs[ADDRESSES] = CoreJson.encodeToString(addressSerializer, addresses + (serverId to uri))
		}
	}

	/** Forgets the account's server, profile and addresses; themes and the client identifier stay. */
	suspend fun clearAccount() {
		store.edit { prefs ->
			prefs.remove(SERVER_ID)
			prefs.remove(PROFILE)
			prefs.remove(ADDRESSES)
		}
	}

	override fun homeLayout(profile: String): Flow<HomeLayout> =
		store.data.map { HomeLayout.decode(it[homeLayoutKey(profile)]) }.distinctUntilChanged()

	override suspend fun setHomeLayout(profile: String, layout: HomeLayout) {
		store.edit { prefs ->
			if (layout.isDefault) prefs.remove(homeLayoutKey(profile)) else prefs[homeLayoutKey(profile)] = layout.encode()
		}
	}

	override suspend fun rewatchDismissals(profile: String): JsonObject? =
		store.data.first()[dismissalsKey(profile)]?.let { runCatching { CoreJson.parseToJsonElement(it).jsonObject }.getOrNull() }

	override suspend fun setRewatchDismissals(profile: String, dismissals: JsonObject) {
		store.edit { it[dismissalsKey(profile)] = dismissals.toString() }
	}

	override fun recentSearches(profile: String): Flow<List<String>> =
		store.data.map { prefs -> prefs[recentsKey(profile)]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty() }.distinctUntilChanged()

	override suspend fun setRecentSearches(profile: String, searches: List<String>) {
		store.edit { prefs ->
			if (searches.isEmpty()) prefs.remove(recentsKey(profile)) else prefs[recentsKey(profile)] = searches.joinToString("\n")
		}
	}

	override val sidebarExpanded: Flow<Boolean?> = store.data.map { it[SIDEBAR_EXPANDED] }.distinctUntilChanged()

	override suspend fun setSidebarExpanded(expanded: Boolean) {
		store.edit { it[SIDEBAR_EXPANDED] = expanded }
	}

	private fun Preferences.themeChoice() = ThemeChoice(
		matchSystem = this[THEME_MATCH] ?: true,
		lightId = this[THEME_LIGHT] ?: ThemeChoice().lightId,
		darkId = this[THEME_DARK] ?: ThemeChoice().darkId,
		fixedMode = if (this[THEME_FIXED] == ThemeMode.Light.name) ThemeMode.Light else ThemeMode.Dark,
	)

	private fun Preferences.addresses(): Map<String, String> =
		this[ADDRESSES]?.let { runCatching { CoreJson.decodeFromString(addressSerializer, it) }.getOrNull() } ?: emptyMap()

	private companion object {
		val THEME_MATCH = booleanPreferencesKey("theme.matchSystem")
		val THEME_LIGHT = stringPreferencesKey("theme.light")
		val THEME_DARK = stringPreferencesKey("theme.dark")
		val THEME_FIXED = stringPreferencesKey("theme.fixedMode")
		val CLIENT_ID = stringPreferencesKey("plex.clientIdentifier")
		val SERVER_ID = stringPreferencesKey("plex.serverId")
		val PROFILE = stringPreferencesKey("plex.profile")
		val ADDRESSES = stringPreferencesKey("plex.addresses")
		val addressSerializer = MapSerializer(String.serializer(), String.serializer())
		val SIDEBAR_EXPANDED = booleanPreferencesKey("shell.sidebarExpanded")

		fun homeLayoutKey(profile: String) = stringPreferencesKey("home.layout.$profile")
		fun dismissalsKey(profile: String) = stringPreferencesKey("home.rewatchDismissed.$profile")
		fun recentsKey(profile: String) = stringPreferencesKey("search.recents.$profile")
	}
}

/** The kept choices of a sign-in. */
class StoredAccount(val serverId: String?, val profile: PlexHomeUser?, val addresses: Map<String, String>)

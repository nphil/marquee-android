package com.nphil.marquee.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.HomeLayout
import com.nphil.marquee.core.TabBarBehavior
import com.nphil.marquee.core.TabLayout
import com.nphil.marquee.ui.theme.HeaderStyle
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** What the app keeps that is not secret: themes, the chosen server, server addresses and how browsing is set up. */
class AppSettings(context: Context) : BrowsePreferences {
	internal val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

	val themeChoice: Flow<ThemeChoice> = store.data.map { it.themeChoice() }.distinctUntilChanged()

	suspend fun updateTheme(change: (ThemeChoice) -> ThemeChoice) {
		store.edit { prefs ->
			val next = change(prefs.themeChoice())
			prefs[THEME_MATCH] = next.matchSystem
			prefs[THEME_LIGHT] = next.lightId
			prefs[THEME_DARK] = next.darkId
			prefs[THEME_FIXED] = next.fixedMode.name
			prefs[HEADER_STYLE] = next.headerStyle.name
			prefs[SCROLL_BARS] = next.showsScrollBars
		}
	}

	internal fun posterLabels(profile: String): Flow<PosterLabels> =
		store.data.map { it.posterLabels(profile) }.distinctUntilChanged()

	internal suspend fun updatePosterLabels(profile: String, change: (PosterLabels) -> PosterLabels) {
		store.edit { prefs -> prefs.setPosterLabels(profile, change(prefs.posterLabels(profile))) }
	}

	/** The trailer and theme music choices of a Home profile; defaults for one that never chose, or no profile. */
	fun playback(profile: String?): Flow<PlaybackPrefs> = store.data.map { it.playbackPrefs(profile) }.distinctUntilChanged()

	suspend fun updatePlayback(profile: String, change: (PlaybackPrefs) -> PlaybackPrefs) {
		store.edit { prefs -> prefs.setPlaybackPrefs(profile, change(prefs.playbackPrefs(profile))) }
	}

	/** How subtitles look for a Home profile (the player's Subtitle Style); the Blend look for one that never chose. */
	fun subtitleLook(profile: String?): Flow<SubtitleLook> = store.data.map { it.subtitleLook(profile) }.distinctUntilChanged()

	suspend fun updateSubtitleLook(profile: String, change: (SubtitleLook) -> SubtitleLook) {
		store.edit { prefs -> prefs.setSubtitleLook(profile, change(prefs.subtitleLook(profile))) }
	}

	/** SpeechDock is off while there is no service address on this device. */
	internal val speechDockAddress: Flow<String?> = store.data.map { it[SPEECH_DOCK] }.distinctUntilChanged()

	internal suspend fun setSpeechDockAddress(address: String?) {
		store.edit { prefs ->
			if (address == null) prefs.remove(SPEECH_DOCK) else prefs[SPEECH_DOCK] = address
		}
	}

	/** What a profile remembers of a title's audio and subtitle picks, as Core wrote it; null when nothing yet. */
	suspend fun trackMemory(key: String): String? = store.data.first()[stringPreferencesKey(key)]

	suspend fun rememberTracks(key: String, memory: String) {
		store.edit { prefs -> prefs[stringPreferencesKey(key)] = memory }
	}

	/** Seerr's address and switches; see [SeerrPrefs]. */
	val seerr: Flow<SeerrPrefs> = store.data.map { it.seerrPrefs() }.distinctUntilChanged()

	suspend fun updateSeerr(change: (SeerrPrefs) -> SeerrPrefs) {
		store.edit { prefs -> prefs.setSeerrPrefs(change(prefs.seerrPrefs())) }
	}

	/** The big-screen plugin's settings; see [BigScreenPrefs]. */
	val bigScreen: Flow<BigScreenPrefs> = store.data.map { it.bigScreenPrefs() }.distinctUntilChanged()

	suspend fun updateBigScreen(change: (BigScreenPrefs) -> BigScreenPrefs) {
		store.edit { prefs -> prefs.setBigScreenPrefs(change(prefs.bigScreenPrefs())) }
	}

	/** Receiver discovery is a device setting, not a setting of the Plex profile. */
	internal val castEnabled = store.data.map { it[CAST_ENABLED] ?: false }.distinctUntilChanged()

	internal suspend fun setCastEnabled(on: Boolean) {
		store.edit { it[CAST_ENABLED] = on }
	}

	internal val airPlayEnabled = store.data.map { it[AIRPLAY_ENABLED] ?: false }.distinctUntilChanged()

	internal suspend fun setAirPlayEnabled(on: Boolean) {
		store.edit { it[AIRPLAY_ENABLED] = on }
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
			addresses = prefs.addresses(),
		)
	}

	suspend fun setServerId(id: String) {
		store.edit { it[SERVER_ID] = id }
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
			prefs.remove(LEGACY_PROFILE)
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

	override val sidebarWidth: Flow<Float?> = store.data.map { it[SIDEBAR_WIDTH] }.distinctUntilChanged()

	override suspend fun setSidebarWidth(dp: Float) {
		store.edit { it[SIDEBAR_WIDTH] = dp }
	}

	override val miniBarHeight: Flow<Float?> = store.data.map { it[MINI_BAR_HEIGHT] }.distinctUntilChanged()

	override suspend fun setMiniBarHeight(dp: Float) {
		store.edit { it[MINI_BAR_HEIGHT] = dp }
	}

	override val tabLayout: Flow<TabLayout> = store.data.map { prefs ->
		prefs[TAB_ORDER]?.let { order -> TabLayout.saved(order.split(','), prefs[TAB_SHOWN].orEmpty().split(',')) } ?: TabLayout.standard
	}.distinctUntilChanged()

	override suspend fun setTabLayout(layout: TabLayout) {
		store.edit { prefs ->
			prefs[TAB_ORDER] = layout.savedOrder.joinToString(",")
			prefs[TAB_SHOWN] = layout.savedShown.joinToString(",")
		}
	}

	override val tabBarBehavior: Flow<TabBarBehavior> =
		store.data.map { prefs -> TabBarBehavior.entries.firstOrNull { it.name == prefs[TAB_BAR] } ?: TabBarBehavior.Always }.distinctUntilChanged()

	override suspend fun setTabBarBehavior(behavior: TabBarBehavior) {
		store.edit { it[TAB_BAR] = behavior.name }
	}

	override fun hiddenLibraries(profile: String): Flow<Set<String>> =
		store.data.map { it[hiddenLibrariesKey(profile)].orEmpty() }.distinctUntilChanged()

	override suspend fun setHiddenLibraries(profile: String, hidden: Set<String>) {
		store.edit { prefs ->
			if (hidden.isEmpty()) prefs.remove(hiddenLibrariesKey(profile)) else prefs[hiddenLibrariesKey(profile)] = hidden
		}
	}

	override fun libraryChoice(profile: String): Flow<String?> =
		store.data.map { it[libraryChoiceKey(profile)] }.distinctUntilChanged()

	override suspend fun setLibraryChoice(profile: String, libraryKey: String) {
		store.edit { it[libraryChoiceKey(profile)] = libraryKey }
	}

	override fun libraryView(profile: String, libraryKey: String): Flow<String?> =
		store.data.map { it[libraryViewKey(profile, libraryKey)] }.distinctUntilChanged()

	override suspend fun setLibraryView(profile: String, libraryKey: String, encoded: String?) {
		store.edit { prefs ->
			if (encoded == null) prefs.remove(libraryViewKey(profile, libraryKey)) else prefs[libraryViewKey(profile, libraryKey)] = encoded
		}
	}

	internal fun liveLists(scope: String): Flow<LiveLists> =
		store.data.map { it.liveLists(liveListsKey(scope)) }.distinctUntilChanged()

	internal suspend fun updateLiveLists(scope: String, change: (LiveLists) -> LiveLists) {
		val key = liveListsKey(scope)
		store.edit { it[key] = CoreJson.encodeToString(LiveLists.serializer(), change(it.liveLists(key))) }
	}

	private fun Preferences.liveLists(key: Preferences.Key<String>): LiveLists =
		this[key]?.let { runCatching { CoreJson.decodeFromString(LiveLists.serializer(), it) }.getOrNull() } ?: LiveLists()

	private fun Preferences.themeChoice() = ThemeChoice(
		matchSystem = this[THEME_MATCH] ?: true,
		lightId = this[THEME_LIGHT] ?: ThemeChoice().lightId,
		darkId = this[THEME_DARK] ?: ThemeChoice().darkId,
		fixedMode = if (this[THEME_FIXED] == ThemeMode.Light.name) ThemeMode.Light else ThemeMode.Dark,
		headerStyle = HeaderStyle.entries.firstOrNull { it.name == this[HEADER_STYLE] } ?: HeaderStyle.Soft,
		showsScrollBars = this[SCROLL_BARS] ?: true,
	)

	private fun Preferences.addresses(): Map<String, String> =
		this[ADDRESSES]?.let { runCatching { CoreJson.decodeFromString(addressSerializer, it) }.getOrNull() } ?: emptyMap()

	private companion object {
		val THEME_MATCH = booleanPreferencesKey("theme.matchSystem")
		val THEME_LIGHT = stringPreferencesKey("theme.light")
		val THEME_DARK = stringPreferencesKey("theme.dark")
		val THEME_FIXED = stringPreferencesKey("theme.fixedMode")
		val HEADER_STYLE = stringPreferencesKey("theme.headerStyle")
		val SCROLL_BARS = booleanPreferencesKey("theme.scrollBars")
		val CLIENT_ID = stringPreferencesKey("plex.clientIdentifier")
		val SERVER_ID = stringPreferencesKey("plex.serverId")
		val LEGACY_PROFILE = stringPreferencesKey("plex.profile")
		val ADDRESSES = stringPreferencesKey("plex.addresses")
		val SPEECH_DOCK = stringPreferencesKey("subtitles.speechDock")
		val addressSerializer = MapSerializer(String.serializer(), String.serializer())
		val SIDEBAR_EXPANDED = booleanPreferencesKey("shell.sidebarExpanded")
		val SIDEBAR_WIDTH = floatPreferencesKey("shell.sidebarWidth")
		val MINI_BAR_HEIGHT = floatPreferencesKey("player.miniBarHeight")
		val TAB_ORDER = stringPreferencesKey("shell.tabOrder")
		val TAB_SHOWN = stringPreferencesKey("shell.tabsShown")
		val TAB_BAR = stringPreferencesKey("shell.tabBarBehavior")
		val CAST_ENABLED = booleanPreferencesKey("cast.enabled")
		val AIRPLAY_ENABLED = booleanPreferencesKey("airplay.enabled")

		fun homeLayoutKey(profile: String) = stringPreferencesKey("home.layout.$profile")
		fun dismissalsKey(profile: String) = stringPreferencesKey("home.rewatchDismissed.$profile")
		fun recentsKey(profile: String) = stringPreferencesKey("search.recents.$profile")
		fun hiddenLibrariesKey(profile: String) = stringSetPreferencesKey("library.hidden.$profile")
		fun libraryChoiceKey(profile: String) = stringPreferencesKey("library.chosen.$profile")
		fun libraryViewKey(profile: String, libraryKey: String) = stringPreferencesKey("library.view.$profile.$libraryKey")
		fun liveListsKey(scope: String) = stringPreferencesKey("live.channels.$scope")
	}
}

/** The kept choices of a sign-in. */
class StoredAccount(val serverId: String?, val addresses: Map<String, String>)

@Serializable
internal data class LiveLists(val favourites: List<String> = emptyList(), val recents: List<String> = emptyList())

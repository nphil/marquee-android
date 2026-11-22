package com.nphil.marquee.data

import com.nphil.marquee.core.HomeLayout
import com.nphil.marquee.core.TabBarBehavior
import com.nphil.marquee.core.TabLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * What browsing keeps between launches. A profile is named by its uuid; the empty string is the account itself. The
 * app keeps these in [AppSettings]; tests keep them in memory.
 */
interface BrowsePreferences {
	/** The profile's choice of Home rows; the server's own order until the profile changes something. */
	fun homeLayout(profile: String): Flow<HomeLayout>

	suspend fun setHomeLayout(profile: String, layout: HomeLayout)

	/** The shows the profile took off Continue Watching, as the bridge hands them back; null when there are none. */
	suspend fun rewatchDismissals(profile: String): JsonObject?

	suspend fun setRewatchDismissals(profile: String, dismissals: JsonObject)

	/** The profile's last searches, newest first. */
	fun recentSearches(profile: String): Flow<List<String>>

	suspend fun setRecentSearches(profile: String, searches: List<String>)

	/** Whether the tablet's sidebar is open; null until the person has chosen. */
	val sidebarExpanded: Flow<Boolean?>

	suspend fun setSidebarExpanded(expanded: Boolean)

	/** The tabs as this device arranged them; [TabLayout.standard] until then. */
	val tabLayout: Flow<TabLayout>

	suspend fun setTabLayout(layout: TabLayout)

	/** What the phone's tab bar does while a page scrolls. */
	val tabBarBehavior: Flow<TabBarBehavior>

	suspend fun setTabBarBehavior(behavior: TabBarBehavior)

	/** The libraries the profile switched off in Favorite Libraries, by library id (the section's uuid, else its key); empty means all are on. */
	fun hiddenLibraries(profile: String): Flow<Set<String>>

	suspend fun setHiddenLibraries(profile: String, hidden: Set<String>)

	/** The key of the library the profile last chose in Library; null until it chooses. */
	fun libraryChoice(profile: String): Flow<String?>

	suspend fun setLibraryChoice(profile: String, libraryKey: String)

	/** One library's sort and filter as the profile left them, in the text Library encoded it as; null until it changes them. */
	fun libraryView(profile: String, libraryKey: String): Flow<String?>

	/** Keeps [encoded] as the library's sort and filter; null clears them back to the defaults. */
	suspend fun setLibraryView(profile: String, libraryKey: String, encoded: String?)
}

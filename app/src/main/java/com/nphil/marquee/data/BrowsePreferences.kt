package com.nphil.marquee.data

import com.nphil.marquee.core.HomeLayout
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
}

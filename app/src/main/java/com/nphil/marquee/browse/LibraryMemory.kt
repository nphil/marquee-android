package com.nphil.marquee.browse

import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.data.BrowsePreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What Library keeps for one profile between launches: the library it left off at, each library's sort and filter, and
 * which libraries the profile switched off in Favorite Libraries. The app keeps these in [BrowsePreferences]; tests
 * keep them in memory.
 */
interface LibraryMemory {
	/** The libraries switched off, by [LibrarySection.libraryId]; follows the Settings page as it changes. */
	val hidden: Flow<Set<String>>

	/** The key of the library chosen last; null until one is. */
	suspend fun choice(): String?

	suspend fun choose(libraryKey: String)

	/** One library's sort and filter in the text [encodeLibraryView] wrote; null while they are the defaults. */
	suspend fun view(libraryKey: String): String?

	suspend fun keepView(libraryKey: String, encoded: String?)
}

/** [LibraryMemory] over the app's preferences. A write runs to its end even when the screen is left meanwhile. */
class StoredLibraryMemory(private val preferences: BrowsePreferences, private val profile: String) : LibraryMemory {
	override val hidden: Flow<Set<String>> = preferences.hiddenLibraries(profile)

	override suspend fun choice(): String? = preferences.libraryChoice(profile).first()

	override suspend fun choose(libraryKey: String) = withContext(NonCancellable) { preferences.setLibraryChoice(profile, libraryKey) }

	override suspend fun view(libraryKey: String): String? = preferences.libraryView(profile, libraryKey).first()

	override suspend fun keepView(libraryKey: String, encoded: String?) =
		withContext(NonCancellable) { preferences.setLibraryView(profile, libraryKey, encoded) }
}

/**
 * The libraries Library offers: the movie and TV libraries the profile did not switch off, or all of them when it
 * switched off every one, as the iPhone does.
 */
fun browsableLibraries(sections: List<LibrarySection>, hidden: Set<String>): List<LibrarySection> {
	val all = sections.filter { it.isBrowsable }
	return all.filter { it.libraryId !in hidden }.ifEmpty { all }
}

private const val VIEW_VERSION = 1

// Defaults stay out of the text, which keeps it short; the sort and the version are always in.
private val ViewJson = Json(CoreJson) { encodeDefaults = false }

@Serializable
private class StoredGenre(val key: String, val title: String)

@Serializable
private class StoredView(
	val v: Int,
	val sort: String,
	val reversed: Boolean = false,
	val unwatched: Boolean = false,
	val genre: StoredGenre? = null,
	val seriesList: String? = null,
)

/**
 * The part of [options] a library keeps between visits: sort, order and filters, not which of All, Collections or
 * Recommended is showing. Versioned JSON; null while they are all the defaults.
 */
fun encodeLibraryView(options: LibraryOptions): String? {
	if (options.sort == Sort.Title && !options.reversed && !options.isFiltered && options.seriesListing == SeriesListing.Shows) return null
	val stored = StoredView(
		v = VIEW_VERSION,
		sort = options.sort.raw,
		reversed = options.reversed,
		unwatched = options.unwatchedOnly,
		genre = options.genre?.let { StoredGenre(it.key, it.title) },
		seriesList = options.seriesListing.takeUnless { it == SeriesListing.Shows }?.name,
	)
	return ViewJson.encodeToString(StoredView.serializer(), stored)
}

/**
 * The options [encodeLibraryView] wrote; the defaults for text that is missing, damaged, or written by a version this
 * one does not know.
 */
fun decodeLibraryView(text: String?): LibraryOptions {
	if (text.isNullOrEmpty()) return LibraryOptions()
	val stored = runCatching { ViewJson.decodeFromString(StoredView.serializer(), text) }.getOrNull() ?: return LibraryOptions()
	val sort = Sort.entries.firstOrNull { it.raw == stored.sort }
	if (stored.v != VIEW_VERSION || sort == null || stored.genre?.key?.isEmpty() == true) return LibraryOptions()
	return LibraryOptions(
		sort = sort,
		reversed = stored.reversed && sort.orders != null,
		unwatchedOnly = stored.unwatched,
		genre = stored.genre?.let { PlexDirectory(key = it.key, title = it.title) },
		seriesListing = SeriesListing.entries.firstOrNull { it.name == stored.seriesList } ?: SeriesListing.Shows,
	)
}

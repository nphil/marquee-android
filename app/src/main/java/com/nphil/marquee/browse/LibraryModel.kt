package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.AlphabetEntry
import com.nphil.marquee.core.Changes
import com.nphil.marquee.core.DeletedTitles
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.Page
import com.nphil.marquee.core.Paged
import com.nphil.marquee.core.PagedLoader
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.core.libraryCollections
import com.nphil.marquee.core.libraryGenres
import com.nphil.marquee.core.libraryLetters
import com.nphil.marquee.core.libraryPage
import com.nphil.marquee.core.libraryRecommended
import com.nphil.marquee.core.refetchItems
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** The three views of one library. */
enum class LibrarySegment(val title: String) { All("All"), Collections("Collections"), Recommended("Recommended") }

/** A filter shown as a chip over the grid, which a tap on it removes. */
sealed interface LibraryChip {
	val title: String

	data object Unwatched : LibraryChip {
		override val title = "Unwatched"
	}

	data class Genre(val genre: PlexDirectory) : LibraryChip {
		override val title: String get() = genre.title
	}
}

/** What the segment control, the View menu and the chips have chosen for one library. */
@Immutable
data class LibraryOptions(
	val segment: LibrarySegment = LibrarySegment.All,
	val sort: Sort = Sort.Title,
	val reversed: Boolean = false,
	val unwatchedOnly: Boolean = false,
	val genre: PlexDirectory? = null,
) {
	fun query(type: MediaType) = SectionQuery(type, sort, reversed, unwatchedOnly, genre)

	val isFiltered: Boolean get() = unwatchedOnly || genre != null

	/** A field starts in its first order: A–Z, newest first. */
	fun sortedBy(field: Sort) = copy(sort = field, reversed = false)

	/** The filters, on the All view only: Collections and Recommended do not take them. */
	val chips: List<LibraryChip>
		get() = if (segment != LibrarySegment.All) emptyList() else listOfNotNull(
			LibraryChip.Unwatched.takeIf { unwatchedOnly },
			genre?.let(LibraryChip::Genre),
		)

	fun without(chip: LibraryChip) = when (chip) {
		LibraryChip.Unwatched -> copy(unwatchedOnly = false)
		is LibraryChip.Genre -> copy(genre = null)
	}

	fun cleared() = copy(unwatchedOnly = false, genre = null)
}

/** The line under a poster in a grid sorted by one field. [quiet] marks a fact about the sort ("Never"), not the title. */
@Immutable
data class SortValue(val text: String, val quiet: Boolean = false)

/** Today and how this device writes dates, for the words under a poster. */
class DayClock(val today: LocalDate, val zone: ZoneId, private val format: DateTimeFormatter) {
	/** "Today", "Yesterday", or the date as the device writes dates. */
	fun day(date: LocalDate): String = when (ChronoUnit.DAYS.between(date, today)) {
		0L -> "Today"
		1L -> "Yesterday"
		else -> format.format(date)
	}

	/** [epochSeconds] as [day] words it. */
	fun day(epochSeconds: Long): String = day(Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate())

	/** Plex's "2024-03-07" as [day] words it; null when the title has no release date. */
	fun releaseDate(value: String?): String? {
		val parts = value?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
		if (parts.size != 3) return null
		return try {
			day(LocalDate.of(parts[0], parts[1], parts[2]))
		} catch (_: java.time.DateTimeException) {
			null
		}
	}

	companion object {
		fun now() = DayClock(LocalDate.now(), ZoneId.systemDefault(), DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
	}
}

/** Whether a grid sorted by this field shows the value under each poster: not Title, which the caption already is, nor Random. */
val Sort.showsValue: Boolean get() = this != Sort.Title && this != Sort.Random

/** The value a grid sorted by [sort] is ordered on, or null for a date or score the title does not have. */
fun PlexItem.sortValue(sort: Sort, clock: DayClock): SortValue? = when (sort) {
	Sort.Title, Sort.Random -> null
	Sort.RecentlyAdded -> addedAt?.let { SortValue(clock.day(it)) }
	Sort.ReleaseDate -> clock.releaseDate(originallyAvailableAt)?.let { SortValue(it) }
	Sort.RecentlyWatched -> lastViewedAt?.let { SortValue(clock.day(it)) } ?: SortValue("Never", quiet = true)
	Sort.Rating -> rating?.let { SortValue(ItemText.score(it)) }
	Sort.AudienceRating -> audienceRating?.let { SortValue(ItemText.score(it)) }
	// A no-break space keeps the count with its noun under a narrow poster.
	Sort.Plays -> if (viewCount == 0) SortValue("Never", quiet = true) else SortValue(if (viewCount == 1) "1\u00A0play" else "$viewCount\u00A0plays")
}

/** [item] as the grid shows it after a show is marked watched or unwatched. */
fun PlexItem.markedWatched(watched: Boolean): PlexItem = copy(
	isWatched = watched,
	unwatchedLeafCount = if (watched) 0 else leafCount ?: unwatchedLeafCount,
	viewedLeafCount = if (watched) leafCount ?: viewedLeafCount else 0,
)

/** Where [offset] sits among [items] once the deleted titles are gone; null while that page is not loaded. */
fun shownIndexOf(items: List<PlexItem>, offset: Int, deleted: DeletedTitles): Int? {
	if (offset !in items.indices) return null
	return deleted.removing(items.subList(0, offset)).size
}

/** What the Library asks of the server, so a test can answer without the native core. */
interface LibrarySource {
	suspend fun sections(): List<LibrarySection>
	suspend fun titles(section: String, query: SectionQuery, start: Int, size: Int): Page<PlexItem>
	suspend fun letters(section: String, query: SectionQuery): List<AlphabetEntry>
	suspend fun genres(section: String): List<PlexDirectory>
	suspend fun collections(section: String, start: Int, size: Int): Page<PlexItem>
	suspend fun recommended(section: String): List<PlexHub>
	suspend fun refetch(ratingKeys: List<String>): List<PlexItem>
}

/** [LibrarySource] over the current server. */
class ServerLibrary(private val source: BrowseSource) : LibrarySource {
	override suspend fun sections() = source.onServer { server, remembered -> sections(server, remembered) }

	override suspend fun titles(section: String, query: SectionQuery, start: Int, size: Int) =
		source.onServer { server, remembered -> libraryPage(server, remembered, section, query, start, size) }

	override suspend fun letters(section: String, query: SectionQuery) =
		source.onServer { server, remembered -> libraryLetters(server, remembered, section, query) }

	override suspend fun genres(section: String) =
		source.onServer { server, remembered -> libraryGenres(server, remembered, section) }

	override suspend fun collections(section: String, start: Int, size: Int) =
		source.onServer { server, remembered -> libraryCollections(server, remembered, section, start, size) }

	override suspend fun recommended(section: String) =
		source.onServer { server, remembered -> libraryRecommended(server, remembered, section) }

	override suspend fun refetch(ratingKeys: List<String>) =
		source.onServer { server, remembered -> refetchItems(server, remembered, ratingKeys) }
}

/**
 * One library as the person browses it: its three views, and the options that shape them. Chosen again, a library starts
 * over with new loaders and the sort and filter it was left with.
 */
class SectionBrowser(
	val section: LibrarySection,
	private val source: LibrarySource,
	private val changes: StateFlow<Changes>,
	private val scope: CoroutineScope,
	initial: LibraryOptions = LibraryOptions(),
	/** Told the sort and filter as [encodeLibraryView] writes them, each time they change, so they can be kept. */
	private val keep: suspend (encoded: String?) -> Unit = {},
) {
	private val type = section.itemType
	private val mutableOptions = MutableStateFlow(initial)
	private val titleLoader = PagedLoader<PlexItem>(scope, keyOf = { it.ratingKey })
	private val collectionLoader = PagedLoader<PlexItem>(scope, keyOf = { it.ratingKey })
	private val recommendedLoader = RemoteLoader<List<PlexHub>>("library.recommended")
	private val letterLoader = RemoteLoader<List<AlphabetEntry>>("library.letters")
	private val genreLoader = RemoteLoader<List<PlexDirectory>>("library.genres")
	private val mutablePulling = MutableStateFlow(false)

	val options: StateFlow<LibraryOptions> = mutableOptions.asStateFlow()

	/** True while a pull to refresh is under way, and not for a load the person did not ask for. */
	val pulling: StateFlow<Boolean> = mutablePulling.asStateFlow()

	/** The titles of the All view, without those deleted since they loaded. */
	val titles: StateFlow<Paged<PlexItem>> = combine(titleLoader.state, changes.map { it.deleted }.distinctUntilChanged()) { paged, deleted ->
		if (deleted.keys.isEmpty()) paged else paged.copy(items = deleted.removing(paged.items))
	}.stateIn(scope, SharingStarted.Eagerly, titleLoader.state.value)

	val collections: StateFlow<Paged<PlexItem>> = collectionLoader.state
	val recommended: StateFlow<Remote<List<PlexHub>>> = recommendedLoader.state
	val genres: StateFlow<List<PlexDirectory>> = genreLoader.state.map { it.value.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
	val letters: StateFlow<List<AlphabetEntry>> = letterLoader.state.map { it.value.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

	/** Whether the A–Z rail is offered: the All view in title order, with more than one letter to go to. */
	val showsRail: StateFlow<Boolean> = combine(mutableOptions, letters, titles) { options, letters, titles ->
		options.segment == LibrarySegment.All && options.query(type).isAlphabetical && letters.size > 1 && titles.items.isNotEmpty()
	}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

	init {
		scope.launch { genreLoader.load { source.genres(section.key) } }
		scope.launch {
			mutableOptions.map { it.query(type) }.distinctUntilChanged().collectLatest { query ->
				titleLoader.reload { start, size -> source.titles(section.key, query, start, size) }
			}
		}
		scope.launch {
			mutableOptions.map { it.query(type).takeIf(SectionQuery::isAlphabetical) }.distinctUntilChanged().collectLatest { query ->
				if (query != null) letterLoader.load { source.letters(section.key, query) }
			}
		}
		scope.launch {
			mutableOptions.map { it.segment }.distinctUntilChanged().collectLatest { segment ->
				when (segment) {
					LibrarySegment.All -> Unit
					LibrarySegment.Collections -> collectionLoader.reload { start, size -> source.collections(section.key, start, size) }
					LibrarySegment.Recommended -> recommendedLoader.load { source.recommended(section.key) }
				}
			}
		}
		scope.launch { followChanges() }
		scope.launch { keepOptions(initial) }
	}

	fun select(segment: LibrarySegment) = mutableOptions.update { it.copy(segment = segment) }

	fun sortBy(field: Sort) = mutableOptions.update { it.sortedBy(field) }

	fun order(reversed: Boolean) = mutableOptions.update { it.copy(reversed = reversed) }

	fun filterGenre(genre: PlexDirectory?) = mutableOptions.update { it.copy(genre = genre) }

	fun filterUnwatched(only: Boolean) = mutableOptions.update { it.copy(unwatchedOnly = only) }

	fun remove(chip: LibraryChip) = mutableOptions.update { it.without(chip) }

	fun clearFilters() = mutableOptions.update { it.cleared() }

	/** Loads whatever the current view shows again, as its Retry does. */
	fun retry() {
		scope.launch {
			when (mutableOptions.value.segment) {
				LibrarySegment.All -> titleLoader.refresh()
				LibrarySegment.Collections -> collectionLoader.refresh()
				LibrarySegment.Recommended -> recommendedLoader.load { source.recommended(section.key) }
			}
		}
	}

	/** A pull to refresh: the current view, and for the All view in title order its letters too. */
	fun pull() {
		if (mutablePulling.value) return
		scope.launch {
			mutablePulling.value = true
			try {
				val options = mutableOptions.value
				when (options.segment) {
					LibrarySegment.All -> {
						titleLoader.refresh()
						val query = options.query(type)
						if (query.isAlphabetical) letterLoader.load { source.letters(section.key, query) }
					}
					LibrarySegment.Collections -> collectionLoader.refresh()
					LibrarySegment.Recommended -> recommendedLoader.load { source.recommended(section.key) }
				}
			} finally {
				mutablePulling.value = false
			}
		}
	}

	suspend fun loadMore(afterKey: String) = titleLoader.loadMoreIfNeeded(afterKey)

	suspend fun loadMoreCollections(afterKey: String) = collectionLoader.loadMoreIfNeeded(afterKey)

	/** Where in the shown titles a rail letter starts, loading the pages up to it first; null when the server has none there. */
	suspend fun indexOf(entry: AlphabetEntry): Int? {
		titleLoader.ensureLoaded(through = entry.offset)
		Diag.debug(DiagCategory.Ui, "library.jump", "letter" to entry.character, "offset" to entry.offset)
		return shownIndexOf(titleLoader.state.value.items, entry.offset, changes.value.deleted)
	}

	/** A show marked watched or unwatched, in the grid and the picks at once. */
	fun patch(item: PlexItem) {
		titleLoader.patch(item)
		recommendedLoader.update { hubs ->
			hubs.map { hub -> hub.copy(items = hub.items.map { if (it.ratingKey == item.ratingKey) item else it }) }
		}
	}

	/** Titles that changed elsewhere since the grid loaded them are asked for again, and deleted ones drop out of the grid. */
	private suspend fun followChanges() {
		var seen = changes.value.revision
		changes.collect { now ->
			if (now.revision == seen) return@collect
			val touched = now.keysAfter(seen)
			seen = now.revision
			val stale = titleLoader.state.value.items.map { it.ratingKey }.filter { it in touched }
			if (stale.isNotEmpty()) scope.launch { refetch(stale) }
		}
	}

	/** Hands each change of the sort or filter to [keep]; moving between the three views changes nothing there. */
	private suspend fun keepOptions(initial: LibraryOptions) {
		var kept = encodeLibraryView(initial)
		mutableOptions.collect { options ->
			val encoded = encodeLibraryView(options)
			if (encoded == kept) return@collect
			kept = encoded
			keep(encoded)
		}
	}

	private suspend fun refetch(keys: List<String>) {
		val fresh = try {
			source.refetch(keys)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.notice(DiagCategory.Ui, "library.refetch.failed", "error" to e.javaClass.simpleName)
			return
		}
		for (item in fresh) titleLoader.patch(item)
	}
}

/** The Library: the libraries the profile favors, and the browsing of the one chosen. */
class LibraryModel(
	private val source: LibrarySource,
	private val changes: StateFlow<Changes>,
	private val memory: LibraryMemory,
	private val scope: CoroutineScope,
) {
	/** What the profile chose last, once read, so the first library never opens only to give way to it. */
	private class Chosen(val key: String?)

	private val loader = RemoteLoader<List<LibrarySection>>("library.sections")
	private val chosen = MutableStateFlow<Chosen?>(null)
	private val mutableBrowser = MutableStateFlow<SectionBrowser?>(null)
	private var browserScope: CoroutineScope? = null

	val sections: StateFlow<Remote<List<LibrarySection>>> = loader.state

	/** The libraries the title menu offers; null until the server's list and the profile's favorites are both in. */
	val libraries: StateFlow<List<LibrarySection>?> = combine(loader.state, memory.hidden) { remote, hidden ->
		remote.value?.let { browsableLibraries(it, hidden) }
	}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

	/** The library shown: the one chosen while it is still offered, else the first. */
	val selected: StateFlow<LibrarySection?> = combine(libraries, chosen) { offered, last ->
		if (offered == null || last == null) null else offered.firstOrNull { it.key == last.key } ?: offered.firstOrNull()
	}.stateIn(scope, SharingStarted.Eagerly, null)

	/** The browsing of [selected], new whenever another library is shown. */
	val browser: StateFlow<SectionBrowser?> = mutableBrowser.asStateFlow()

	init {
		reload()
		scope.launch { chosen.compareAndSet(null, Chosen(memory.choice())) }
		scope.launch {
			selected.distinctUntilChangedBy { it?.key }.collectLatest { section -> open(section) }
		}
	}

	fun reload() {
		scope.launch { loader.load { source.sections() } }
	}

	fun choose(key: String) {
		if (chosen.value?.key == key) return
		chosen.value = Chosen(key)
		scope.launch { memory.choose(key) }
	}

	private suspend fun open(section: LibrarySection?) {
		val previous = browserScope
		if (section == null) {
			browserScope = null
			mutableBrowser.value = null
			previous?.cancel()
			return
		}
		val kept = decodeLibraryView(memory.view(section.key))
		Diag.debug(DiagCategory.Ui, "library.open", "section" to section.key, "type" to section.type)
		val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
		browserScope = child
		mutableBrowser.value = SectionBrowser(section, source, changes, child, kept) { memory.keepView(section.key, it) }
		previous?.cancel()
	}
}

/** Holds a [LibraryModel] for the Library destination, so the chosen library and its loads outlive a rotation. */
class LibraryViewModel(env: BrowseEnvironment, profile: String) : ViewModel() {
	val model = LibraryModel(ServerLibrary(env.source), env.changes.state, StoredLibraryMemory(env.preferences, profile), viewModelScope)
}

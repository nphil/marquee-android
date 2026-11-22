package com.nphil.marquee.ui.requests

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrCatalogue
import com.nphil.marquee.core.SeerrDetails
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrGenre
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrNamed
import com.nphil.marquee.core.SeerrPager
import com.nphil.marquee.core.SeerrProvider
import com.nphil.marquee.core.SeerrRequest
import com.nphil.marquee.core.SeerrStreaming
import com.nphil.marquee.core.SeerrTitle
import com.nphil.marquee.seerr.Seerr
import com.nphil.marquee.seerr.SeerrText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Discover: the rows of Core's catalogue, each loaded on its own so one failing row does not hide the rest. */
class DiscoverModel(private val seerr: Seerr) : ViewModel() {
	@Immutable
	data class Shelf(
		val row: SeerrCatalogue.Row,
		val titles: List<SeerrTitle> = emptyList(),
		val genres: List<SeerrGenre> = emptyList(),
	) {
		val isEmpty: Boolean get() = titles.isEmpty() && genres.isEmpty()
	}

	@Immutable
	data class State(
		val shelves: List<Shelf> = emptyList(),
		/** A load is on its way; what was shown stays until it arrives. */
		val loading: Boolean = true,
		/** The first failure of the last load, in words; shown only when no row has anything to show. */
		val failure: String? = null,
		val loaded: Boolean = false,
	) {
		val failed: Boolean get() = !loading && failure != null && shelves.all { it.isEmpty }
		val nothing: Boolean get() = !loading && loaded && failure == null && shelves.all { it.isEmpty }
	}

	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState.asStateFlow()
	private var job: Job? = null
	private var loadedAt = 0L

	init {
		// A request changes badges on every shelf.
		viewModelScope.launch { seerr.requestsSent.drop(1).collect { load(force = true) } }
	}

	/** Loads the rows unless they are fresh: the screen asks every time it appears. */
	fun load(force: Boolean = false) {
		val fresh = mutableState.value.loaded && SystemClock.elapsedRealtime() - loadedAt < FRESH_MILLIS
		if (!force && (fresh || job?.isActive == true)) return
		job?.cancel()
		job = viewModelScope.launch {
			mutableState.update { it.copy(loading = true) }
			val rows = seerr.catalogue().rows
			if (mutableState.value.shelves.map { it.row.id } != rows.map { it.id }) {
				mutableState.update { it.copy(shelves = rows.map(::Shelf)) }
			}
			var failure: String? = null
			coroutineScope {
				for (row in rows) {
					launch {
						try {
							val loaded = load(row)
							mutableState.update { state -> state.copy(shelves = state.shelves.map { if (it.row.id == row.id) loaded else it }) }
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							if (failure == null) failure = SeerrText.failure(e)
						}
					}
				}
			}
			loadedAt = SystemClock.elapsedRealtime()
			mutableState.update { it.copy(loading = false, loaded = true, failure = failure) }
		}
	}

	private suspend fun load(row: SeerrCatalogue.Row): Shelf = when {
		row.shelf != null -> Shelf(row, titles = seerr.discover(row.shelf))
		row.genres != null -> Shelf(row, genres = seerr.genreCards(row.genres))
		else -> Shelf(row)
	}

	private companion object {
		/** Rows younger than this are not asked for again when the screen reappears. */
		const val FRESH_MILLIS = 300_000L
	}
}

/** My Requests: the account's recent requests, newest first, as Core returns them. */
class MyRequestsModel(private val seerr: Seerr) : ViewModel() {
	@Immutable
	data class State(
		val requests: List<SeerrRequest> = emptyList(),
		val loading: Boolean = true,
		val loaded: Boolean = false,
		val failure: String? = null,
	)

	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState.asStateFlow()
	private var job: Job? = null

	init {
		viewModelScope.launch { seerr.requestsSent.drop(1).collect { load() } }
	}

	/** Loads the list; one on its way is replaced. What was shown stays until the new list arrives or fails. */
	fun load() {
		job?.cancel()
		job = viewModelScope.launch {
			mutableState.update { it.copy(loading = true, failure = null) }
			try {
				val requests = seerr.requests()
				mutableState.update { it.copy(requests = requests, loading = false, loaded = true) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				mutableState.update { it.copy(loading = false, failure = SeerrText.failure(e)) }
			}
		}
	}

	/** Loads once, when the page first appears. */
	fun loadIfNeeded() {
		val state = mutableState.value
		if (!state.loaded && job?.isActive != true) load()
	}
}

/** Search: what is typed, and the titles Seerr finds for it a moment after the last key. */
class SearchModel(private val seerr: Seerr) : ViewModel() {
	@Immutable
	sealed interface State {
		/** Nothing typed, or only spaces. */
		data object Idle : State

		data object Loading : State

		/** [query] is what was asked, trimmed, for the empty message. */
		data class Found(val query: String, val titles: List<SeerrTitle>) : State

		data class Failed(val message: String) : State
	}

	private val mutableText = MutableStateFlow("")
	val text: StateFlow<String> = mutableText.asStateFlow()
	private val mutableState = MutableStateFlow<State>(State.Idle)
	val state: StateFlow<State> = mutableState.asStateFlow()
	private val retries = MutableStateFlow(0)

	init {
		viewModelScope.launch {
			// Typing and a retry both start a search; a newer one replaces one still going.
			combine(mutableText, retries) { text, retry -> text.trim() to retry }
				.collectLatest { (query, retry) ->
					if (query.isEmpty()) {
						mutableState.value = State.Idle
						return@collectLatest
					}
					if (retry == 0) delay(DEBOUNCE_MILLIS)
					search(query)
				}
		}
		// A request changes the badges on the titles shown.
		viewModelScope.launch {
			seerr.requestsSent.drop(1).collect {
				val shown = mutableState.value as? State.Found ?: return@collect
				try {
					mutableState.value = State.Found(shown.query, seerr.search(shown.query))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					// The titles shown stay; their badges are one request behind.
				}
			}
		}
	}

	fun edit(text: String) {
		mutableText.value = text
		retries.value = 0
	}

	fun retry() {
		retries.update { it + 1 }
	}

	private suspend fun search(query: String) {
		mutableState.value = State.Loading
		mutableState.value = try {
			State.Found(query, seerr.search(query))
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			State.Failed(SeerrText.failure(e))
		}
	}

	private companion object {
		const val DEBOUNCE_MILLIS = 300L
	}
}

/** What the View menu offers beyond Core's fixed lists: Seerr's languages and streaming regions, and the services of a region. */
@Immutable
data class FilterOptions(
	val languages: List<SeerrNamed> = emptyList(),
	val regions: List<SeerrNamed> = emptyList(),
	val providers: List<SeerrProvider> = emptyList(),
	/** The region [providers] are for. */
	val providersRegion: String? = null,
)

/** One list to page through: the trending feed, or a query whose filters the View menu changes. */
class BrowseModel(private val seerr: Seerr, private val initial: SeerrFeed) : ViewModel() {
	val pager = SeerrPager()

	private val mutableQuery = MutableStateFlow(initial.query)

	/** The query being listed; null for the trending feed, which has no filters. */
	val query: StateFlow<SeerrBrowseQuery?> = mutableQuery.asStateFlow()

	private val mutableCatalogue = MutableStateFlow<SeerrCatalogue?>(null)
	val catalogue: StateFlow<SeerrCatalogue?> = mutableCatalogue.asStateFlow()

	private val mutableGenres = MutableStateFlow<List<SeerrGenre>>(emptyList())

	/** The genres of the query's kind, for the chip row. */
	val genres: StateFlow<List<SeerrGenre>> = mutableGenres.asStateFlow()

	private val mutableOptions = MutableStateFlow(FilterOptions())
	val options: StateFlow<FilterOptions> = mutableOptions.asStateFlow()

	private val mutablePickedRegion = MutableStateFlow<String?>(null)

	/** The region picked in the View menu, if any; [region] is what the streaming filter uses. */
	val pickedRegion: StateFlow<String?> = mutablePickedRegion.asStateFlow()
	private var job: Job? = null
	private var genresJob: Job? = null
	private var providersJob: Job? = null
	private var optionsLoaded = false

	init {
		viewModelScope.launch { mutableCatalogue.value = seerr.catalogue() }
		reload()
		loadGenres()
		// A request changes the badges of the titles already shown; the list keeps its length and place.
		viewModelScope.launch { seerr.requestsSent.drop(1).collect { pager.refreshLoaded() } }
	}

	private fun feed(): SeerrFeed = mutableQuery.value?.let { SeerrFeed(query = it) } ?: initial

	fun reload() {
		job?.cancel()
		val feed = feed()
		job = viewModelScope.launch { pager.reload { page -> seerr.browse(feed, page) } }
	}

	fun refresh() {
		viewModelScope.launch { pager.refresh() }
	}

	fun loadMoreIfNeeded(after: String) {
		viewModelScope.launch { pager.loadMoreIfNeeded(after) }
	}

	fun loadMore() {
		viewModelScope.launch { pager.loadMore() }
	}

	/** Changes the query and lists it from the start. */
	fun edit(change: (SeerrBrowseQuery) -> SeerrBrowseQuery) {
		val before = mutableQuery.value ?: return
		val after = change(before)
		if (after == before) return
		mutableQuery.value = after
		reload()
		if (after.kind != before.kind) loadGenres()
		if (after.kind != before.kind || after.streaming?.region != before.streaming?.region) loadProviders()
	}

	/** Movies or series: a genre and a streaming service belong to one kind, so they are cleared. */
	fun setKind(kind: SeerrKind) = edit { it.copy(kind = kind, genre = null, streaming = null) }

	fun clearFilters() = edit { it.clearedFilters() }

	/** Picks a streaming service in the region the menu is showing, or none. */
	fun setStreaming(provider: Int?, region: String) {
		mutablePickedRegion.value = region
		edit { it.copy(streaming = provider?.let { id -> SeerrStreaming(id, region) }) }
		loadProviders()
	}

	/** Picks the region whose services are offered; a service already chosen moves with it only when it is picked again. */
	fun setRegion(region: String) {
		mutablePickedRegion.value = region
		edit { query -> query.streaming?.let { query.copy(streaming = null) } ?: query }
		loadProviders()
	}

	/** The region the streaming filter asks for, by the rules in [RequestsRules.effectiveRegion]. */
	fun region(query: SeerrBrowseQuery): String =
		RequestsRules.effectiveRegion(query, mutablePickedRegion.value, Locale.getDefault().country, mutableOptions.value.regions)

	/** Loads the lists the View menu needs, the first time it opens. */
	fun loadOptions() {
		if (optionsLoaded) return
		optionsLoaded = true
		viewModelScope.launch {
			val languages = quiet { seerr.languages() }
			val regions = quiet { seerr.watchRegions() }
			mutableOptions.update { it.copy(languages = languages.orEmpty(), regions = regions.orEmpty()) }
			loadProviders()
		}
	}

	private fun loadGenres() {
		val kind = mutableQuery.value?.kind ?: return
		genresJob?.cancel()
		genresJob = viewModelScope.launch { mutableGenres.value = quiet { seerr.genres(kind) }.orEmpty() }
	}

	private fun loadProviders() {
		val query = mutableQuery.value ?: return
		if (!optionsLoaded) return
		providersJob?.cancel()
		providersJob = viewModelScope.launch {
			val region = region(query)
			val providers = quiet { seerr.watchProviders(query.kind, region) }.orEmpty()
			mutableOptions.update { it.copy(providers = providers, providersRegion = region) }
		}
	}

	/** A list the menu can do without: a failure leaves it empty, and the menu says there is nothing to pick. */
	private suspend fun <T> quiet(block: suspend () -> T): T? = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		null
	}
}

/** A title's page. */
class TitleModel(private val seerr: Seerr, private val kind: SeerrKind, private val tmdbId: Int) : ViewModel() {
	@Immutable
	sealed interface State {
		data object Loading : State

		data class Loaded(val details: SeerrDetails) : State

		data class Failed(val message: String) : State
	}

	private val mutableState = MutableStateFlow<State>(State.Loading)
	val state: StateFlow<State> = mutableState.asStateFlow()
	private var job: Job? = null

	init {
		load()
		// A request changes the status, and what can be requested next.
		viewModelScope.launch { seerr.requestsSent.drop(1).collect { load() } }
	}

	/** Loads the page again, keeping what is shown until the new one arrives. */
	fun load() {
		job?.cancel()
		job = viewModelScope.launch {
			try {
				mutableState.value = State.Loaded(seerr.details(kind, tmdbId))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (mutableState.value !is State.Loaded) mutableState.value = State.Failed(SeerrText.failure(e))
			}
		}
	}

	/** Shows the skeleton and loads again: the page's own Retry. */
	fun retry() {
		mutableState.value = State.Loading
		load()
	}

	/** Sends a request; returns why Seerr refused it, or null once Seerr has it. */
	suspend fun send(is4K: Boolean, seasons: Set<Int>): Refusal? = try {
		seerr.request(kind, tmdbId, seasons, is4K)
		null
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Refusal(SeerrText.failure(e), SeerrText.alreadyRequested(e))
	}

	/** [already]: everything asked for was requested or available, so asking again cannot help. */
	data class Refusal(val message: String, val already: Boolean)
}

package com.nphil.marquee.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pages through one Seerr list by page number, for the browse grid. It is used from one thread, the main one.
 *
 * - Every [reload] starts a new generation; a page that arrives for an older one is dropped, so quick filter
 *   changes cannot mix two lists.
 * - The next page starts when one of the last [LEAD] titles scrolls into view.
 * - Titles already shown are skipped on append: TMDB's order shifts while paging, so pages overlap.
 */
class SeerrPager {
	data class State(
		val items: List<SeerrTitle> = emptyList(),
		/** True while the first page of a reload is on its way; later pages load without showing anything. */
		val isLoading: Boolean = false,
		/** The first page's failure; with nothing to show, the screen offers a retry. */
		val failure: Throwable? = null,
		/** A later page failed: the titles so far stay, and [loadMore] tries again. */
		val moreFailed: Boolean = false,
		/** The first page arrived, even when it held nothing. */
		val hasLoaded: Boolean = false,
		val hasMore: Boolean = false,
	)

	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState

	private var fetch: (suspend (page: Int) -> SeerrPage)? = null
	private var nextPage = 1
	private var isLoadingMore = false
	private var generation = 0

	/** Starts over with a new list and loads its first page. The old titles go at once, so the screen shows its skeleton. */
	suspend fun reload(fetch: suspend (page: Int) -> SeerrPage) {
		this.fetch = fetch
		mutableState.update { it.copy(items = emptyList(), hasLoaded = false) }
		load(pages = 1)
	}

	/** Loads the first page of the same list again. Titles already shown stay until it arrives, and after a failure. */
	suspend fun refresh() = load(pages = 1)

	/**
	 * Loads every page shown so far again, so what a title's badge says is current while the list keeps its length and
	 * the screen its place. Titles already shown stay until all of them arrive, and after a failure.
	 */
	suspend fun refreshLoaded() = load(pages = maxOf(1, nextPage - 1))

	private suspend fun load(pages: Int) {
		val fetch = fetch ?: return
		generation += 1
		val current = generation
		isLoadingMore = false
		mutableState.update { it.copy(isLoading = true, failure = null, moreFailed = false) }
		try {
			val titles = mutableListOf<SeerrTitle>()
			var last: SeerrPage? = null
			for (number in 1..pages) {
				val page = fetch(number)
				if (current != generation) return
				titles += page.titles
				last = page
				if (!page.hasMore) break
			}
			if (last != null) {
				nextPage = last.page + 1
				mutableState.update { it.copy(items = unique(titles), hasMore = last.hasMore, hasLoaded = true) }
			}
		} catch (e: CancellationException) {
			// A load cancelled because its screen went away is not a failure; the screen loads again when it returns.
			if (current == generation) mutableState.update { it.copy(isLoading = false) }
			throw e
		} catch (e: Exception) {
			if (current != generation) return
			mutableState.update { it.copy(failure = e) }
		}
		mutableState.update { it.copy(isLoading = false) }
	}

	/** Call when the title with [id] appears; loads the next page once it is near the end. */
	suspend fun loadMoreIfNeeded(after: String) {
		val state = mutableState.value
		if (state.moreFailed || state.items.takeLast(LEAD).none { it.id == after }) return
		loadMore()
	}

	/** Loads the next page, or tries again after a failure. */
	suspend fun loadMore() {
		val fetch = fetch ?: return
		val start = mutableState.value
		if (!start.hasMore || start.isLoading || isLoadingMore) return
		isLoadingMore = true
		mutableState.update { it.copy(moreFailed = false) }
		val current = generation
		try {
			// A page can hold only titles already shown; the list ends only at Seerr's last page.
			while (mutableState.value.hasMore && current == generation) {
				val page = try {
					fetch(nextPage)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					if (current == generation) mutableState.update { it.copy(moreFailed = true) }
					break
				}
				if (current != generation) return
				val seen = mutableState.value.items.mapTo(HashSet()) { it.id }
				val fresh = page.titles.filter { seen.add(it.id) }
				nextPage = page.page + 1
				mutableState.update { it.copy(items = it.items + fresh, hasMore = page.hasMore) }
				if (fresh.isNotEmpty()) break
			}
		} finally {
			if (current == generation) isLoadingMore = false
		}
	}

	private fun unique(titles: List<SeerrTitle>): List<SeerrTitle> {
		val seen = HashSet<String>()
		return titles.filter { seen.add(it.id) }
	}

	companion object {
		/** How many titles from the end the next page starts: three rows of the widest grid. */
		const val LEAD = 9
	}
}

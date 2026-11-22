package com.nphil.marquee.core

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads a long list page by page for a grid, by the rules of MarqueeCore's PagedLoader:
 * - Every [reload] starts a new generation; a page that arrives for an older one is dropped, so quick filter changes
 *   cannot mix two lists or leave [Paged.isLoading] stuck.
 * - The next page starts loading while the reader is still three quarters of a page from the end, which gives the
 *   request enough lead that a fast fling does not reach the end of the list.
 * - Items already in the list are skipped on append; Plex pages can overlap when the library changes while paging,
 *   and duplicate keys break a lazy grid.
 *
 * Not thread-safe: call it from one dispatcher, the main thread.
 */
class PagedLoader<Item : Any>(
	/** Owns the page requests, so a page lands even when the cell that asked for it scrolls away. */
	private val scope: CoroutineScope,
	val pageSize: Int = 60,
	private val keyOf: (Item) -> String,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val mutable = MutableStateFlow(Paged<Item>())
	val state: StateFlow<Paged<Item>> = mutable.asStateFlow()

	private var fetch: (suspend (start: Int, size: Int) -> Page<Item>)? = null

	/** Server offset of the next page. Counts items as the server sent them, before duplicates are skipped. */
	private var nextStart = 0
	private var hasMore = false
	private var nextPageGeneration: Int? = null

	/** The page on its way, so a jump can wait for it rather than give up. */
	private var nextPage: Job? = null
	private var generation = 0

	/** Sets the query (captured by [fetch]) and loads the first page. The current items stay visible until the new page replaces them. */
	suspend fun reload(fetch: suspend (start: Int, size: Int) -> Page<Item>) {
		this.fetch = fetch
		loadFirstPage(keepingItemsOnFailure = false)
	}

	/**
	 * Loads the first page again with the last query. When that fails, the items already shown stay, with
	 * [Paged.failure] set, so a refresh while offline keeps the last good list on screen.
	 */
	suspend fun refresh() = loadFirstPage(keepingItemsOnFailure = true)

	private suspend fun loadFirstPage(keepingItemsOnFailure: Boolean) {
		val fetch = fetch ?: return
		val current = ++generation
		nextPageGeneration = null
		mutable.update { it.copy(isLoading = true, failure = if (keepingItemsOnFailure) it.failure else null) }
		try {
			val page = fetch(0, pageSize)
			if (current != generation) return
			nextStart = page.items.size
			hasMore = nextStart < page.total && page.items.isNotEmpty()
			mutable.update {
				it.copy(items = page.items, total = page.total, failure = null, loadedAt = now(), revision = it.revision + 1)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (current != generation) return
			if (keepingItemsOnFailure) {
				mutable.update { it.copy(failure = e) }
			} else {
				hasMore = false
				mutable.update { it.copy(items = emptyList(), total = null, failure = e, revision = it.revision + 1) }
			}
		} finally {
			// A load cancelled because its screen went away is not a failure; the screen loads again when it returns.
			if (current == generation) mutable.update { it.copy(isLoading = false) }
		}
	}

	/** Call when the item with [key] appears; loads the next page once it is near the end. */
	suspend fun loadMoreIfNeeded(afterKey: String) {
		if (!canLoadMore) return
		val items = mutable.value.items
		if (items.takeLast(maxOf(1, pageSize * 3 / 4)).none { keyOf(it) == afterKey }) return
		loadNext(pageSize)
	}

	/**
	 * Loads as far as [index] in one request, e.g. for an A–Z jump to a letter further down. A page already on its way
	 * is waited for first, since a second request cannot start beside it.
	 */
	suspend fun ensureLoaded(through: Int) {
		if (nextPageGeneration != null) nextPage?.join()
		if (!canLoadMore || through < nextStart) return
		loadNext(through - nextStart + pageSize)
	}

	/** Replaces a loaded item in place. Returns false when it is not loaded. */
	fun patch(item: Item): Boolean {
		val index = mutable.value.items.indexOfFirst { keyOf(it) == keyOf(item) }
		if (index < 0) return false
		mutable.update { it.copy(items = it.items.toMutableList().also { list -> list[index] = item }, revision = it.revision + 1) }
		return true
	}

	private val canLoadMore: Boolean
		get() = hasMore && !mutable.value.isLoading && nextPageGeneration == null && fetch != null

	private suspend fun loadNext(size: Int) {
		val fetch = fetch ?: return
		val current = generation
		val start = nextStart
		nextPageGeneration = current
		val job = scope.launch {
			try {
				val page = fetch(start, size)
				if (current != generation) return@launch
				val seen = mutable.value.items.mapTo(HashSet(), keyOf)
				val fresh = page.items.filter { seen.add(keyOf(it)) }
				nextStart += page.items.size
				hasMore = nextStart < page.total && page.items.isNotEmpty()
				mutable.update { it.copy(items = it.items + fresh, total = page.total, revision = it.revision + 1) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (current == generation) mutable.update { it.copy(failure = e) }
			} finally {
				if (nextPageGeneration == current) nextPageGeneration = null
			}
		}
		nextPage = job
		job.join()
	}
}

/** What a [PagedLoader] holds at one moment. */
@Immutable
data class Paged<Item>(
	val items: List<Item> = emptyList(),
	/** True while the first page of a reload is loading; later pages load without showing anything. */
	val isLoading: Boolean = false,
	val failure: Throwable? = null,
	/** Items behind the whole request. */
	val total: Int? = null,
	/** Bumped whenever [items] is replaced or extended. */
	val revision: Int = 0,
	/** When the first page last arrived, in epoch milliseconds. */
	val loadedAt: Long? = null,
)

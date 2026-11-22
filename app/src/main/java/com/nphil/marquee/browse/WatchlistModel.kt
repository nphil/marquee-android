package com.nphil.marquee.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.Page
import com.nphil.marquee.core.Paged
import com.nphil.marquee.core.PagedLoader
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.Watchlist
import com.nphil.marquee.core.watchlistPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The titles saved on the profile's plex.tv Watchlist, a page at a time; it refetches when this app saves or removes one. */
class WatchlistModel(private val env: BrowseEnvironment) : ViewModel() {
	private val loader = PagedLoader<PlexItem>(viewModelScope, keyOf = { it.ratingKey })
	private val actions = ItemActions(env)

	/** The saved titles, without those taken off here that plex.tv may still list for a moment. */
	val state: StateFlow<Paged<PlexItem>> = combine(loader.state, env.changes.state.map { it.unlisted }.distinctUntilChanged()) { paged, unlisted ->
		if (unlisted.isEmpty()) {
			paged
		} else {
			paged.copy(
				items = paged.items.filterNot { item ->
					val key = item.guid?.let(Watchlist::catalogueKey)
					key != null && key in unlisted
				},
			)
		}
	}.stateIn(viewModelScope, SharingStarted.Eagerly, Paged())

	init {
		viewModelScope.launch {
			env.changes.state.map { it.watchlistRevision }.distinctUntilChanged().drop(1).collect { loader.refresh() }
		}
		load()
	}

	fun load() {
		viewModelScope.launch { loader.reload(::page) }
	}

	fun refresh() {
		viewModelScope.launch { loader.refresh() }
	}

	/** The reader is at the poster with [lastKey]. */
	fun nearEnd(lastKey: String) {
		viewModelScope.launch { loader.loadMoreIfNeeded(lastKey) }
	}

	/** Takes [item] off the Watchlist, with Undo in the message; the poster goes at once. */
	fun remove(item: PlexItem) {
		val guid = item.guid ?: return
		actions.setWatchlisted(false, guid) { on -> if (on) env.changes.showInWatchlist(guid) else env.changes.hideFromWatchlist(guid) }
	}

	private suspend fun page(start: Int, size: Int): Page<PlexItem> =
		env.source.onAccount { token -> watchlistPage(token, start, size) } ?: error("Signed out of plex.tv")
}

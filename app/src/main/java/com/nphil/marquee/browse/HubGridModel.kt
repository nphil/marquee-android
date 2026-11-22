package com.nphil.marquee.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.Paged
import com.nphil.marquee.core.PagedLoader
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.serverList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A hub's whole list, behind its "See all": the titles the server holds under [key], a page at a time. */
class HubGridModel(private val env: BrowseEnvironment, private val key: String) : ViewModel() {
	private val loader = PagedLoader<PlexItem>(viewModelScope, keyOf = { it.ratingKey })

	val state: StateFlow<Paged<PlexItem>> = loader.state

	init {
		load()
	}

	fun load() {
		viewModelScope.launch {
			loader.reload { start, size -> env.source.onServer { server, remembered -> serverList(server, remembered, key, start, size) } }
		}
	}

	fun refresh() {
		viewModelScope.launch { loader.refresh() }
	}

	/** The reader is at the poster with [lastKey]. */
	fun nearEnd(lastKey: String) {
		viewModelScope.launch { loader.loadMoreIfNeeded(lastKey) }
	}
}

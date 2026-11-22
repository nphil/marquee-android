package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.searchHubs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a search covers: everything, or one kind of title. [hub] is the server's name for that kind of group. */
enum class SearchScope(val label: String, val hub: String?) {
	All("All", null),
	Movies("Movies", "movie"),
	Shows("Shows", "show"),
	Episodes("Episodes", "episode"),
}

@Immutable
data class SearchUi(
	/** What the field holds right now; the search follows it a moment later. */
	val query: String = "",
	val scope: SearchScope = SearchScope.All,
	/** What the settled query found, in the groups the server sent. */
	val results: Remote<List<PlexHub>> = Remote(),
	/** The profile's last searches, newest first. */
	val recents: List<String> = emptyList(),
) {
	/** The groups of [results] the [scope] covers; people are not listed. */
	val groups: List<PlexHub>
		get() = results.value.orEmpty().filter { it.items.isNotEmpty() && it.type != "actor" && (scope.hub == null || it.type == scope.hub) }
}

/** The profile's recent searches with [search] first and an earlier copy of it (any case) gone, at most [limit] of them. */
internal fun recentSearchesWith(recents: List<String>, search: String, limit: Int = 4): List<String> {
	val text = search.trim()
	if (text.isEmpty()) return recents
	return (listOf(text) + recents.filterNot { it.equals(text, ignoreCase = true) }).take(limit)
}

/** Search (DESIGN §8.2): the server's groups for what is typed, a moment after the typing stops. */
@OptIn(FlowPreview::class)
class SearchModel(private val env: BrowseEnvironment, private val place: BrowsePlace) : ViewModel() {
	private val typed = MutableStateFlow("")
	private val scope = MutableStateFlow(SearchScope.All)
	private val results = MutableStateFlow(Remote<List<PlexHub>>())

	val ui: StateFlow<SearchUi> = combine(typed, scope, results, env.preferences.recentSearches(place.profile)) { query, scope, results, recents ->
		SearchUi(query, scope, results, recents)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi())

	init {
		viewModelScope.launch {
			typed.debounce(DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged().collectLatest { search(it) }
		}
	}

	fun type(text: String) {
		typed.value = text
	}

	fun choose(scope: SearchScope) {
		this.scope.value = scope
	}

	fun retry() {
		viewModelScope.launch { search(typed.value.trim()) }
	}

	/** Keeps the search among the recent ones: the person submitted it or opened a result of it. */
	fun keep(query: String) = saveRecents { recentSearchesWith(it, query) }

	fun forget(search: String) = saveRecents { recents -> recents.filterNot { it.equals(search, ignoreCase = true) } }

	fun clearRecents() = saveRecents { emptyList() }

	private fun saveRecents(change: (List<String>) -> List<String>) {
		viewModelScope.launch {
			val current = env.preferences.recentSearches(place.profile).first()
			env.preferences.setRecentSearches(place.profile, change(current))
		}
	}

	private suspend fun search(query: String) {
		if (query.isEmpty()) {
			results.value = Remote()
			return
		}
		results.update { it.copy(isLoading = true, failure = null) }
		try {
			val groups = env.source.onServer { server, remembered -> searchHubs(server, remembered, query, LIMIT) }
			results.value = Remote(groups, System.currentTimeMillis())
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.warning(DiagCategory.Plex, "search.failed", "error" to e.javaClass.simpleName)
			results.update { it.copy(failure = e, isLoading = false) }
		}
	}

	private companion object {
		const val DEBOUNCE_MS = 300L
		const val LIMIT = 25
	}
}

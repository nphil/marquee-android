package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.LibrarySection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One library on the Favorite Libraries page: whether it is switched on, and whether it is the only one left on. */
@Immutable
data class FavoriteLibrary(val section: LibrarySection, val on: Boolean, val isLast: Boolean)

/** The libraries with a poster grid, each with its switch; [hidden] holds the library ids switched off. */
fun favoriteLibraries(sections: List<LibrarySection>, hidden: Set<String>): List<FavoriteLibrary> {
	val browsable = sections.filter { it.isBrowsable }
	val remaining = browsable.count { it.libraryId !in hidden }
	return browsable.map { section ->
		val on = section.libraryId !in hidden
		FavoriteLibrary(section, on, isLast = on && remaining == 1)
	}
}

/** [hidden] with [section] switched on or off. The last library on stays on, so switching it off changes nothing. */
fun withLibrary(hidden: Set<String>, sections: List<LibrarySection>, section: LibrarySection, on: Boolean): Set<String> {
	if (on) return hidden - section.libraryId
	val last = favoriteLibraries(sections, hidden).any { it.section.libraryId == section.libraryId && it.isLast }
	return if (last) hidden else hidden + section.libraryId
}

/** What the Favorite Libraries page draws: the server's libraries once asked, and the switches saved for the profile. */
@Immutable
data class FavoriteLibrariesUi(val remote: Remote<List<FavoriteLibrary>> = Remote())

/**
 * Settings › Favorite Libraries: which of the server's libraries the profile sees. A switch is saved at once; Home and
 * the Library follow it.
 */
class FavoriteLibrariesModel(private val env: BrowseEnvironment, private val place: BrowsePlace) : ViewModel() {
	private val loader = RemoteLoader<List<LibrarySection>>("settings.libraries")

	/** The switches as edited here, ahead of the store, so a second tap in quick succession starts from the first. */
	private val hidden = MutableStateFlow<Set<String>?>(null)

	val ui: StateFlow<FavoriteLibrariesUi> = combine(loader.state, hidden) { remote, hidden ->
		// The switches are drawn only once the saved ones are read, so none flashes on and then off.
		val sections = remote.value
		val rows = if (sections == null || hidden == null) null else favoriteLibraries(sections, hidden)
		FavoriteLibrariesUi(Remote(rows, remote.loadedAt, remote.failure, remote.isLoading))
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoriteLibrariesUi())

	init {
		viewModelScope.launch { env.preferences.hiddenLibraries(place.profile).collect { hidden.value = it } }
	}

	/** Asks the server for its libraries. */
	fun load() {
		viewModelScope.launch {
			loader.load { env.source.onServer { server, remembered -> sections(server, remembered) } }
		}
	}

	fun setOn(section: LibrarySection, on: Boolean) {
		val sections = loader.state.value.value ?: return
		val before = hidden.value ?: return
		val after = withLibrary(before, sections, section, on)
		if (after == before) return
		hidden.value = after
		env.scope.launch { env.preferences.setHiddenLibraries(place.profile, after) }
	}
}

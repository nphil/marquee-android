package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.HomeLayout
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.homeCatalogue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Home Rows editor draws. */
@Immutable
data class HomeRowsUi(
	val remote: Remote<List<HomeRow>> = Remote(),
	/** Every row the server offers, in the order and with the switches the profile chose. */
	val rows: List<HomeRow> = emptyList(),
	/** The profile has changed nothing: the server's rows in its order. */
	val isDefault: Boolean = true,
)

/**
 * The Home Rows editor (DESIGN §8.1): every row the server could show, with a switch and a place in the order. Each
 * change is saved at once, so Home follows it behind the sheet.
 */
class HomeRowsModel(private val env: BrowseEnvironment, private val place: BrowsePlace) : ViewModel() {
	private val loader = RemoteLoader<List<HomeRow>>("home.rows")

	/** The layout as edited here, ahead of the store: a second change in quick succession starts from the first. */
	private val layout = MutableStateFlow(HomeLayout())

	val ui: StateFlow<HomeRowsUi> = combine(loader.state, layout) { remote, layout ->
		HomeRowsUi(remote, layout.arrange(remote.value.orEmpty()), layout.isDefault)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeRowsUi())

	init {
		viewModelScope.launch { env.preferences.homeLayout(place.profile).collect { layout.value = it } }
	}

	/** Asks the server for its rows, with a few titles each to preview. */
	fun load() {
		viewModelScope.launch {
			loader.load { env.source.onServer { server, remembered -> homeCatalogue(server, remembered) } }
		}
	}

	fun setShown(row: HomeRow, shown: Boolean) =
		edit { rows -> rows.map { if (it.id == row.id) it.copy(shown = shown) else it } }

	/** Puts the row at [from] where [to] is. */
	fun move(from: Int, to: Int) = edit { rows ->
		if (from !in rows.indices || to !in rows.indices) rows else rows.toMutableList().also { it.add(to, it.removeAt(from)) }
	}

	/** Brings back the server's rows in its order. */
	fun reset() = apply(HomeLayout())

	private fun edit(change: (List<HomeRow>) -> List<HomeRow>) {
		val current = layout.value
		val rows = current.arrange(loader.state.value.value.orEmpty())
		if (rows.isEmpty()) return
		apply(current.saving(change(rows)))
	}

	private fun apply(next: HomeLayout) {
		layout.value = next
		env.scope.launch { env.preferences.setHomeLayout(place.profile, next) }
	}
}

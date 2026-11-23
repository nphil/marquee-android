package com.nphil.marquee.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.titlesOf
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.ExtraPlayer
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.Person
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.pageScroll
import com.nphil.marquee.ui.player.LocalDockRoom
import com.nphil.marquee.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The titles a cast member is in on the server. */
class PersonModel(private val container: AppContainer, private val id: String) : ViewModel() {
	data class State(val titles: List<PlexItem>? = null, val failure: CoreException.Kind? = null)

	private val mutable = MutableStateFlow(State())
	val state: StateFlow<State> = mutable.asStateFlow()

	init {
		load()
	}

	fun load() {
		mutable.value = State()
		viewModelScope.launch {
			mutable.value = try {
				State(titles = container.session.onServer { server, remembered -> container.core.titlesOf(server, remembered, id) })
			} catch (e: CoreException) {
				Diag.warning(DiagCategory.Ui, "person.failed", "kind" to e.kind.name, "status" to e.status)
				State(failure = e.kind)
			}
		}
	}
}

/** A person's page: a grid of the titles they are in, which open like any poster. */
@Composable
fun PersonScreen(container: AppContainer, route: Person, navigator: Navigator) {
	val model = viewModel { PersonModel(container, route.id) }
	val state by model.state.collectAsStateWithLifecycle()
	val changes by container.changes.state.collectAsStateWithLifecycle()
	val titles = state.titles?.let(changes::shown)
	val failure = state.failure
	val grid = rememberLazyGridState()
	// The player's bar, while it shows, sits above the navigation bar and takes the room under the page.
	val room = LocalDockRoom.current
	BrowseScaffold(topBar = {
		TopAppBar(title = { Text(route.name) }, navigationIcon = { BackArrow(navigator::back) })
	}) { padding ->
		val bottom = padding.calculateBottomPadding() + room
		when {
			titles != null && titles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				EmptyState(R.drawable.ic_person, "Nothing on this server", "${route.name} isn't in any title here.")
			}
			titles != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
				val gap = Space.s3
				val columns = maxOf(2, ((maxWidth - Space.inset * 2 + gap) / (130.dp + gap)).toInt())
				val width = (maxWidth - Space.inset * 2 - gap * (columns - 1)) / columns
				LazyVerticalGrid(
					columns = GridCells.Fixed(columns),
					modifier = Modifier.fillMaxSize().pageScroll(grid, top = padding.calculateTopPadding(), bottom = bottom),
					state = grid,
					contentPadding = PaddingValues(start = Space.inset, end = Space.inset, top = padding.calculateTopPadding() + Space.s3, bottom = bottom + Space.s8),
					horizontalArrangement = Arrangement.spacedBy(gap),
					verticalArrangement = Arrangement.spacedBy(Space.s4),
				) {
					items(titles, key = { it.ratingKey }) { title ->
						ItemPoster(title, width, onClick = { navigator.openItem(title.ratingKey) })
					}
				}
			}
			failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				LoadFailure(failure, container.session.state.value.server?.name ?: "the server", model::load)
			}
		}
	}
}

/** A trailer or other extra, full screen. */
@Composable
fun ExtraScreen(item: PlexItem, navigator: Navigator) {
	ExtraPlayer(item, onClose = navigator::back)
}

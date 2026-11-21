package com.nphil.marquee.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.IconBadge
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.plusInset
import com.nphil.marquee.ui.servers.ContentWidth
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

/** The current server's library sections, loaded again when the server or its token changes. */
class LibraryViewModel(private val session: AppSession) : ViewModel() {
	sealed interface Sections {
		data object Loading : Sections
		data class Loaded(val sections: List<LibrarySection>) : Sections
		data class Failed(val error: CoreException.Kind) : Sections
	}

	private val attempts = MutableStateFlow(0)

	@OptIn(ExperimentalCoroutinesApi::class)
	val sections: StateFlow<Sections> = combine(
		session.state.map { it.server }.distinctUntilChanged(),
		attempts,
	) { server, _ -> server }
		.transformLatest { server ->
			if (server == null) return@transformLatest
			emit(Sections.Loading)
			emit(
				try {
					Sections.Loaded(session.sections(server))
				} catch (e: CoreException) {
					Sections.Failed(e.kind)
				},
			)
		}
		// Kept while Settings is open, so returning to the Library shows it at once.
		.stateIn(viewModelScope, SharingStarted.Lazily, Sections.Loading)

	fun retry() = attempts.update { it + 1 }
}

/** The Library tab: the server's sections by name and kind. */
@Composable
fun LibraryScreen(session: AppSession, model: LibraryViewModel) {
	val sections by model.sections.collectAsStateWithLifecycle()
	val state = session.state.collectAsStateWithLifecycle()
	val serverName by remember { derivedStateOf { state.value.server?.name.orEmpty() } }
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	Scaffold(
		modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
		topBar = {
			LargeFlexibleTopAppBar(
				title = { Text("Library") },
				subtitle = { Text(serverName) },
				scrollBehavior = scrollBehavior,
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		when (val shown = sections) {
			LibraryViewModel.Sections.Loading -> Centered(padding) { LoadingIndicator() }
			is LibraryViewModel.Sections.Failed -> Centered(padding) { Failure(shown.error, serverName, model::retry) }
			is LibraryViewModel.Sections.Loaded -> if (shown.sections.isEmpty()) {
				Centered(padding) {
					EmptyState(
						icon = R.drawable.ic_video_library,
						title = "No libraries",
						message = "This server shares no libraries.",
						action = { RetryButton("Refresh", onClick = model::retry) },
					)
				}
			} else {
				SectionList(shown.sections, padding)
			}
		}
	}
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
	Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SectionList(sections: List<LibrarySection>, padding: PaddingValues) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = padding.plusInset(),
		verticalArrangement = Arrangement.spacedBy(GroupGap),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		itemsIndexed(sections, key = { _, section -> section.key }) { index, section ->
			val kind = SectionKind.of(section.type)
			GroupRow(index = index, count = sections.size, modifier = Modifier.widthIn(max = ContentWidth)) {
				IconBadge(kind.icon)
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
					Text(section.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
					Text(kind.word, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
				}
			}
		}
	}
}

@Composable
private fun Failure(error: CoreException.Kind, server: String, retry: () -> Unit) {
	val unreachable = error == CoreException.Kind.Unreachable || error == CoreException.Kind.NoConnections
	EmptyState(
		icon = if (unreachable) R.drawable.ic_wifi_off else R.drawable.ic_error,
		title = if (unreachable) "Can't reach $server" else "Something went wrong",
		message = if (unreachable) "Check that it is on and this tablet is online." else "$server sent an answer Marquee did not expect.",
		action = { RetryButton("Retry", onClick = retry) },
	)
}

private enum class SectionKind(val word: String, @DrawableRes val icon: Int) {
	Movies("Movies", R.drawable.ic_movie),
	Shows("TV Shows", R.drawable.ic_tv),
	Music("Music", R.drawable.ic_music_note),
	Photos("Photos", R.drawable.ic_photo_library),
	Other("Library", R.drawable.ic_video_library),
	;

	companion object {
		/** Plex's section types. */
		fun of(type: String) = when (type) {
			"movie" -> Movies
			"show" -> Shows
			"artist" -> Music
			"photo" -> Photos
			else -> Other
		}
	}
}

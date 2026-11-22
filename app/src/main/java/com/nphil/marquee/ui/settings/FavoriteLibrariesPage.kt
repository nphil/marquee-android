package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.FavoriteLibrariesModel
import com.nphil.marquee.browse.FavoriteLibrary
import com.nphil.marquee.container
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.browse.RemoteContent
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * Settings › Favorite Libraries: which of the server's libraries the profile sees. The others leave Home and the
 * Library's title menu; Settings keeps listing every one. At least one stays on.
 */
@Composable
fun FavoriteLibrariesPage(session: AppSession, contentPadding: PaddingValues) {
	val env = LocalContext.current.container.browse
	val place by env.source.place.collectAsStateWithLifecycle()
	val where = place
	if (where == null) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			EmptyState(R.drawable.ic_dns, "No server chosen", "Choose a server to pick its libraries.")
		}
		return
	}
	val model: FavoriteLibrariesModel = viewModel(key = "settings.libraries:${where.key}") { FavoriteLibrariesModel(env, where) }
	LaunchedEffect(model) { model.load() }
	val ui by model.ui.collectAsStateWithLifecycle()
	RemoteContent(
		remote = ui.remote,
		serverName = where.server.name,
		onRetry = model::load,
		onSignIn = session::signOut,
		modifier = Modifier.fillMaxSize(),
		skeleton = {},
	) { libraries ->
		if (libraries.isEmpty()) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				EmptyState(R.drawable.ic_video_library, "No libraries", "The server has no movie or show libraries.")
			}
		} else {
			LibraryList(libraries, model, contentPadding)
		}
	}
}

@Composable
private fun LibraryList(libraries: List<FavoriteLibrary>, model: FavoriteLibrariesModel, contentPadding: PaddingValues) {
	BrowseList(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "heading") { Heading("Which libraries this device shows.") }
		itemsIndexed(libraries, key = { _, library -> library.section.libraryId }) { index, library ->
			GroupRow(index, libraries.size, onClick = if (library.isLast) null else ({ model.setOn(library.section, !library.on) })) {
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
					Text(
						library.section.title,
						style = MaterialTheme.typography.titleMedium,
						color = if (library.isLast) lucent.ink2 else lucent.ink,
					)
					if (library.isLast) {
						Text("At least one library stays on.", style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
					}
				}
				Switch(checked = library.on, onCheckedChange = null, enabled = !library.isLast)
			}
		}
		item(key = "footer") {
			SettingsFootnote(
				"Your favorites fill Home's shelves and the Library's title menu; Settings lists every one you have.",
				Modifier.padding(top = Space.s2),
			)
		}
	}
}

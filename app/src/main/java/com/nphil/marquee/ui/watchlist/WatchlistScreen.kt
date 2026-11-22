package com.nphil.marquee.ui.watchlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.WatchlistModel
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.ActionMenu
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.MenuAction
import com.nphil.marquee.ui.browse.PagedPosterGrid
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.theme.lucent

/**
 * The Watchlist (DESIGN §8.3): the titles saved on plex.tv, as a grid of posters. A title opens its page here when the
 * server has it, else its page on the Watchlist; a long press offers to take it off.
 * [onBack] shows a back arrow when the page was opened from a menu rather than as a tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(env: BrowseEnvironment, place: BrowsePlace, onSignIn: () -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
	val model: WatchlistModel = viewModel(key = "watchlist:${place.key}") { WatchlistModel(env) }
	val paged by model.state.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	BrowseScaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text("Watchlist") },
				navigationIcon = { if (onBack != null) BackArrow(onBack) },
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
	) { padding ->
		PullToRefreshBox(
			isRefreshing = paged.isLoading && paged.items.isNotEmpty(),
			onRefresh = model::refresh,
			modifier = Modifier.padding(padding).fillMaxSize(),
		) {
			PagedPosterGrid(
				paged = paged,
				serverName = "plex.tv",
				onRetry = model::load,
				onSignIn = onSignIn,
				onNearEnd = model::nearEnd,
				modifier = Modifier.fillMaxSize(),
				banner = false,
				empty = {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						EmptyState(
							R.drawable.ic_bookmark,
							"Nothing saved",
							"Tap the bookmark on a title to save it here, from this device or any Plex app.",
						)
					}
				},
			) { item, width ->
				var menu by remember { mutableStateOf(false) }
				val removable = item.guid != null
				Box {
					ItemPoster(
						item = item,
						width = width,
						onClick = { navigator?.openWatchlistTitle(item) },
						onLongClick = if (removable) ({ menu = true }) else null,
						onLongClickLabel = if (removable) "More options" else null,
					)
					if (menu) {
						ActionMenu(
							expanded = true,
							onDismiss = { menu = false },
							actions = listOf(
								MenuAction(R.drawable.ic_bookmark_remove, "Remove from Watchlist", destructive = true) { model.remove(item) },
							),
						)
					}
				}
			}
		}
	}
}

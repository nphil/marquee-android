package com.nphil.marquee.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.HomeModel
import com.nphil.marquee.browse.HomeUi
import com.nphil.marquee.browse.HubGridModel
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.ActionButton
import com.nphil.marquee.ui.browse.ContinueSkeleton
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.PagedPosterGrid
import com.nphil.marquee.ui.browse.RemoteContent
import com.nphil.marquee.ui.browse.ShelfSkeleton
import com.nphil.marquee.ui.browse.continueCardWidth
import com.nphil.marquee.ui.browse.posterMinWidth
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * Home (DESIGN §8.1): the server's hubs in the rows the profile chose, Continue Watching first with its rewatch cards, and
 * the Watchlist as a shelf while it is not a tab. The last Home shows at once and is refreshed behind it; a pull to refresh
 * asks again. A hub's "See all" opens its whole list over Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
	env: BrowseEnvironment,
	place: BrowsePlace,
	watchlistShelf: Boolean,
	onOpenWatchlist: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
	trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
	val model: HomeModel = viewModel(key = "home:${place.key}") { HomeModel(env, place) }
	LaunchedEffect(model, watchlistShelf) { model.showWatchlistShelf(watchlistShelf) }
	val ui by model.ui.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val openWatchlist by rememberUpdatedState(onOpenWatchlist)
	var editing by rememberSaveable { mutableStateOf(false) }
	var hubKey by rememberSaveable { mutableStateOf<String?>(null) }
	var hubTitle by rememberSaveable { mutableStateOf("") }

	val actions = remember(model, navigator) {
		HomeActions(
			open = { navigator?.openItem(it.ratingKey) },
			openWatchlistTitle = { navigator?.openWatchlistTitle(it) },
			seeAll = { row ->
				row.hub.key?.let {
					hubKey = it
					hubTitle = row.title
				}
			},
			seeAllWatchlist = { openWatchlist() },
			hideRow = model::hide,
			editHome = { editing = true },
			markWatched = model::markWatched,
			markShowWatched = model::markShowWatched,
			removeFromContinueWatching = model::removeFromContinueWatching,
			dismissRewatch = model::dismissRewatch,
			setWatched = model::setWatched,
			removeFromWatchlist = model::removeFromWatchlist,
		)
	}

	val open = hubKey
	if (open != null) {
		HubGridScreen(env, place, open, hubTitle, onBack = { hubKey = null }, onSignIn = onSignIn, modifier = modifier)
		return
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text("Home") },
				actions = {
					IconButton(onClick = { editing = true }) {
						Icon(painterResource(R.drawable.ic_tune), contentDescription = "Edit Home")
					}
					trailing()
				},
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		PullToRefreshBox(
			isRefreshing = ui.pulling,
			onRefresh = model::refresh,
			modifier = Modifier.padding(padding).fillMaxSize(),
		) {
			BoxWithConstraints(Modifier.fillMaxSize()) {
				val posterWidth = posterMinWidth(maxWidth)
				val cardWidth = continueCardWidth(maxWidth)
				RemoteContent(
					remote = ui.remote,
					serverName = place.server.name,
					onRetry = model::retry,
					onSignIn = onSignIn,
					skeleton = { HomeSkeleton(posterWidth, cardWidth) },
				) {
					Shelves(ui, posterWidth, cardWidth, actions, onEdit = { editing = true }, onRefresh = model::refresh)
				}
			}
		}
	}
	if (editing) HomeRowsSheet(env, place, onSignIn = onSignIn, onDismiss = { editing = false })
}

@Composable
private fun HomeSkeleton(posterWidth: Dp, cardWidth: Dp) {
	Column(Modifier.padding(vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s7)) {
		ContinueSkeleton(cardWidth)
		ShelfSkeleton(posterWidth)
		ShelfSkeleton(posterWidth)
	}
}

/** A shelf of Home: a row of the layout, or the Watchlist shelf. */
private sealed interface Shelf {
	val key: String

	data class Row(val row: HomeRow) : Shelf {
		override val key: String get() = row.id
	}

	data class Watchlist(val titles: List<PlexItem>, val more: Boolean) : Shelf {
		override val key: String get() = "watchlist"
	}
}

/** The shelves in order; the Watchlist follows Continue Watching, and leads when there is no such row. */
private fun shelves(ui: HomeUi): List<Shelf> {
	val rows: MutableList<Shelf> = ui.rows.mapTo(ArrayList()) { Shelf.Row(it) }
	if (ui.watchlist.isNotEmpty()) {
		val after = ui.rows.indexOfFirst { it.continueWatching }
		rows.add(after + 1, Shelf.Watchlist(ui.watchlist, ui.watchlistMore))
	}
	return rows
}

@Composable
private fun Shelves(ui: HomeUi, posterWidth: Dp, cardWidth: Dp, actions: HomeActions, onEdit: () -> Unit, onRefresh: () -> Unit) {
	val shelves = remember(ui.rows, ui.watchlist, ui.watchlistMore) { shelves(ui) }
	when {
		ui.allHidden -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			EmptyState(R.drawable.ic_tune, "Every row is hidden", "Choose the rows Home shows.") {
				ActionButton(R.drawable.ic_tune, "Edit Home", onEdit)
			}
		}
		shelves.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			EmptyState(R.drawable.ic_video_library, "Nothing here yet", "Add some on the server.") {
				RetryButton("Refresh", onRefresh)
			}
		}
		else -> LazyColumn(
			Modifier.fillMaxSize(),
			contentPadding = PaddingValues(vertical = Space.s3),
			verticalArrangement = Arrangement.spacedBy(Space.s7),
		) {
			items(shelves, key = { it.key }, contentType = { if (it is Shelf.Watchlist) "watchlist" else "row" }) { shelf ->
				when (shelf) {
					is Shelf.Watchlist -> WatchlistShelf(shelf.titles, shelf.more, posterWidth, actions)
					is Shelf.Row ->
						if (shelf.row.continueWatching) ContinueShelf(shelf.row, cardWidth, actions)
						else PosterShelf(shelf.row, posterWidth, actions)
				}
			}
		}
	}
}

/** A hub's whole list, over Home; Back returns to Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubGridScreen(
	env: BrowseEnvironment,
	place: BrowsePlace,
	key: String,
	title: String,
	onBack: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val model: HubGridModel = viewModel(key = "hub:${place.key}:$key") { HubGridModel(env, key) }
	val paged by model.state.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	BackHandler(onBack = onBack)
	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text(title) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		PullToRefreshBox(
			isRefreshing = paged.isLoading && paged.items.isNotEmpty(),
			onRefresh = model::refresh,
			modifier = Modifier.padding(padding).fillMaxSize(),
		) {
			PagedPosterGrid(
				paged = paged,
				serverName = place.server.name,
				onRetry = model::load,
				onSignIn = onSignIn,
				onNearEnd = model::nearEnd,
				modifier = Modifier.fillMaxSize(),
				empty = { EmptyState(R.drawable.ic_video_library, "Nothing here", "The server lists no titles here.") },
			) { item, width ->
				ItemPoster(item, width, onClick = { navigator?.openItem(item.ratingKey) })
			}
		}
	}
}

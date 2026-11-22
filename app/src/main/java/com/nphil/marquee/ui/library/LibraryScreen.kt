package com.nphil.marquee.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.DayClock
import com.nphil.marquee.browse.ItemActions
import com.nphil.marquee.browse.LibraryOptions
import com.nphil.marquee.browse.LibrarySegment
import com.nphil.marquee.browse.LibraryViewModel
import com.nphil.marquee.browse.SectionBrowser
import com.nphil.marquee.browse.markedWatched
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.Paged
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.ActionButton
import com.nphil.marquee.ui.browse.ActionMenu
import com.nphil.marquee.ui.browse.FailureKind
import com.nphil.marquee.ui.browse.GridSkeleton
import com.nphil.marquee.ui.browse.HubGridScreen
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.MenuAction
import com.nphil.marquee.ui.browse.OfflineBanner
import com.nphil.marquee.ui.browse.PagedPosterGrid
import com.nphil.marquee.ui.browse.PosterGridMetrics
import com.nphil.marquee.ui.browse.RemoteContent
import com.nphil.marquee.ui.browse.ShelfSkeleton
import com.nphil.marquee.ui.browse.failureKind
import com.nphil.marquee.ui.browse.posterMinWidth
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.SectionHeader
import com.nphil.marquee.ui.components.ShelfRow
import com.nphil.marquee.ui.shell.Places
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Library destination: the server's movie and TV libraries, one at a time. The title names the library and opens a
 * menu of the favorite libraries and the places that are not tabs; the View menu sorts and filters the titles; the
 * segments choose All, Collections or Recommended; and in title order the A–Z rail jumps to a letter. A Search or a
 * Requests that is not a tab gets a button of its own. [onBack] adds a back arrow, for a Library opened from Home's menu
 * rather than as a tab. [trailing] adds the app bar's own actions after the others.
 */
@Composable
fun LibraryScreen(
	env: BrowseEnvironment,
	place: BrowsePlace,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
	places: Places = Places.None,
	onBack: (() -> Unit)? = null,
	trailing: @Composable RowScope.() -> Unit = {},
) {
	val host: LibraryViewModel = viewModel(key = "library:${place.key}") { LibraryViewModel(env, place.profile) }
	val model = host.model
	val sections by model.sections.collectAsStateWithLifecycle()
	val libraries by model.libraries.collectAsStateWithLifecycle()
	val selected by model.selected.collectAsStateWithLifecycle()
	val browser by model.browser.collectAsStateWithLifecycle()
	val actions = remember(env) { ItemActions(env) }
	var hubKey by rememberSaveable { mutableStateOf<String?>(null) }
	var hubTitle by rememberSaveable { mutableStateOf("") }
	val open = hubKey
	if (open != null) {
		HubGridScreen(env, place, open, hubTitle, onBack = { hubKey = null }, onSignIn = onSignIn, modifier = modifier)
		return
	}

	BackHandler(enabled = onBack != null) { onBack?.invoke() }
	val inMenu = remember(places) { places.inLibraryMenu() }

	BrowseScaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = {
					LibraryTitle(selected?.title ?: "Library", libraries.orEmpty(), selected?.key, inMenu, model::choose, places.open)
				},
				navigationIcon = { onBack?.let { BackArrow(it) } },
				actions = {
					if (Destination.Search in places.others) {
						PlaceButton(Destination.Search, { places.open(Destination.Search) }, Modifier.testTag(LibraryTags.Search))
					}
					browser?.let { current ->
						val options by current.options.collectAsStateWithLifecycle()
						val genres by current.genres.collectAsStateWithLifecycle()
						if (options.segment == LibrarySegment.All) {
							ViewMenuButton(
								options = options,
								genres = genres,
								onSort = current::sortBy,
								onOrder = current::order,
								onGenre = current::filterGenre,
								onUnwatched = current::filterUnwatched,
							)
						}
					}
					if (Destination.Requests in places.others) {
						PlaceButton(Destination.Requests, { places.open(Destination.Requests) }, Modifier.testTag(LibraryTags.Requests))
					}
					trailing()
				},
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
	) { padding ->
		BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
			val posterWidth = posterMinWidth(maxWidth)
			val fontScale = LocalDensity.current.fontScale
			val skeletonWidth = maxWidth
			val skeleton: @Composable () -> Unit = {
				GridSkeleton(
					PosterGridMetrics.of(skeletonWidth - Space.inset * 2, fontScale),
					Modifier.padding(start = Space.inset, top = Space.s3),
				)
			}
			RemoteContent(
				remote = sections,
				serverName = place.server.name,
				onRetry = model::reload,
				onSignIn = onSignIn,
				modifier = Modifier.fillMaxSize(),
				skeleton = skeleton,
			) {
				val offered = libraries
				val current = browser
				when {
					offered == null -> skeleton()
					offered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						EmptyState(
							R.drawable.ic_video_library,
							"No libraries",
							"This server shares no movie or TV libraries.",
							action = { RetryButton("Refresh", model::reload) },
						)
					}
					current == null -> skeleton()
					else -> key(current) {
						SectionContent(
							browser = current,
							place = place,
							actions = actions,
							posterWidth = posterWidth,
							onSignIn = onSignIn,
							onSeeAll = { hub ->
								hubKey = hub.key
								hubTitle = hub.title
							},
						)
					}
				}
			}
		}
	}
}

@Composable
private fun SectionContent(
	browser: SectionBrowser,
	place: BrowsePlace,
	actions: ItemActions,
	posterWidth: Dp,
	onSignIn: () -> Unit,
	onSeeAll: (PlexHub) -> Unit,
) {
	val options by browser.options.collectAsStateWithLifecycle()
	Column(Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s2),
			verticalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			SegmentControl(options.segment, browser::select, Modifier.widthIn(max = 480.dp).fillMaxWidth())
			if (options.chips.isNotEmpty()) FilterChips(options.chips, browser::remove)
		}
		Box(Modifier.weight(1f).fillMaxWidth()) {
			when (options.segment) {
				LibrarySegment.All -> TitlesView(browser, options, place, actions, onSignIn)
				LibrarySegment.Collections -> CollectionsView(browser, place, onSignIn)
				LibrarySegment.Recommended -> RecommendedView(browser, place, actions, posterWidth, onSignIn, onSeeAll)
			}
		}
	}
}

/** The offline banner over a grid that kept its titles through a failed refresh. */
@Composable
private fun PagedBanner(paged: Paged<PlexItem>, serverName: String, onRetry: () -> Unit) {
	val failure = paged.failure
	val loadedAt = paged.loadedAt
	if (paged.items.isNotEmpty() && failure != null && loadedAt != null && failure.failureKind() != FailureKind.Unauthorized) {
		OfflineBanner(serverName, loadedAt, isRetrying = paged.isLoading, onRetry = onRetry)
	}
}

@Composable
private fun TitlesView(browser: SectionBrowser, options: LibraryOptions, place: BrowsePlace, actions: ItemActions, onSignIn: () -> Unit) {
	val paged by browser.titles.collectAsStateWithLifecycle()
	val showsRail by browser.showsRail.collectAsStateWithLifecycle()
	val letters by browser.letters.collectAsStateWithLifecycle()
	val pulling by browser.pulling.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val scope = rememberCoroutineScope()
	val query = options.query(browser.section.itemType)
	// A new sort or filter starts at the top, as a new list does.
	key(query) {
		val gridState = rememberLazyGridState()
		val clock = remember { DayClock.now() }
		var jump by remember { mutableStateOf<Job?>(null) }
		Column(Modifier.fillMaxSize()) {
			PagedBanner(paged, place.server.name, browser::retry)
			Box(Modifier.weight(1f).fillMaxWidth()) {
				PullToRefreshBox(isRefreshing = pulling, onRefresh = browser::pull, modifier = Modifier.fillMaxSize()) {
					PagedPosterGrid(
						paged = paged,
						serverName = place.server.name,
						onRetry = browser::retry,
						onSignIn = onSignIn,
						onNearEnd = { key -> scope.launch { browser.loadMore(key) } },
						modifier = Modifier.fillMaxSize().testTag(LibraryTags.Grid),
						state = gridState,
						// Room for the rail is kept while it may appear, so the columns do not change as the letters arrive.
						endInset = if (query.isAlphabetical) Space.s7 else Space.inset,
						banner = false,
						empty = {
							if (options.isFiltered) {
								EmptyState(
									R.drawable.ic_filter_list,
									"No matches",
									"Nothing in ${browser.section.title} fits these filters.",
									action = { ActionButton(R.drawable.ic_close, "Clear Filters", browser::clearFilters) },
								)
							} else {
								EmptyState(
									R.drawable.ic_video_library,
									if (browser.section.itemType == MediaType.Show) "No shows yet" else "No movies yet",
									"Add some on the server.",
									action = { RetryButton("Refresh", browser::retry) },
								)
							}
						},
					) { item, width ->
						ShowMenuPoster(
							item = item,
							width = width,
							sort = query.sort,
							clock = clock,
							onOpen = { navigator?.openItem(item.ratingKey) },
							onMark = { watched ->
								actions.setWatched(watched, item) { now -> browser.patch(item.markedWatched(now)) }
							},
						)
					}
				}
				if (showsRail && letters.size > 1) {
					AlphabetRail(
						entries = letters,
						onSelect = { entry ->
							jump?.cancel()
							jump = scope.launch {
								val index = browser.indexOf(entry) ?: return@launch
								gridState.scrollToItem(index.coerceAtMost(maxOf(gridState.layoutInfo.totalItemsCount - 1, 0)))
							}
						},
						modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = Space.s4),
					)
				}
			}
		}
	}
}

@Composable
private fun CollectionsView(browser: SectionBrowser, place: BrowsePlace, onSignIn: () -> Unit) {
	val paged by browser.collections.collectAsStateWithLifecycle()
	val pulling by browser.pulling.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val scope = rememberCoroutineScope()
	Column(Modifier.fillMaxSize()) {
		PagedBanner(paged, place.server.name, browser::retry)
		PullToRefreshBox(isRefreshing = pulling, onRefresh = browser::pull, modifier = Modifier.weight(1f).fillMaxWidth()) {
			PagedPosterGrid(
				paged = paged,
				serverName = place.server.name,
				onRetry = browser::retry,
				onSignIn = onSignIn,
				onNearEnd = { key -> scope.launch { browser.loadMoreCollections(key) } },
				modifier = Modifier.fillMaxSize(),
				banner = false,
				empty = {
					EmptyState(R.drawable.ic_folder_open, "No collections", "${browser.section.title} has no collections yet.")
				},
			) { item, width ->
				ItemPoster(item, width, onClick = { navigator?.openItem(item.ratingKey) })
			}
		}
	}
}

@Composable
private fun RecommendedView(
	browser: SectionBrowser,
	place: BrowsePlace,
	actions: ItemActions,
	posterWidth: Dp,
	onSignIn: () -> Unit,
	onSeeAll: (PlexHub) -> Unit,
) {
	val remote by browser.recommended.collectAsStateWithLifecycle()
	val pulling by browser.pulling.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	PullToRefreshBox(isRefreshing = pulling, onRefresh = browser::pull, modifier = Modifier.fillMaxSize()) {
		RemoteContent(
			remote = remote,
			serverName = place.server.name,
			onRetry = browser::retry,
			onSignIn = onSignIn,
			modifier = Modifier.fillMaxSize(),
			skeleton = {
				Column(Modifier.padding(vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s7)) {
					ShelfSkeleton(posterWidth)
					ShelfSkeleton(posterWidth)
				}
			},
		) { hubs ->
			val shelves = remember(hubs) { hubs.filter { it.items.isNotEmpty() } }
			if (shelves.isEmpty()) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					EmptyState(
						R.drawable.ic_star,
						"Nothing to suggest",
						"The server has no picks from ${browser.section.title} yet.",
					)
				}
			} else {
				BrowseList(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(top = Space.s3, bottom = Space.s7),
					verticalArrangement = Arrangement.spacedBy(Space.s7),
				) {
					itemsIndexed(shelves, key = { index, hub -> "$index:${hub.identifier}" }) { _, hub ->
						Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
							SectionHeader(
								hub.title,
								onSeeAll = if (hub.more && hub.key != null) ({ onSeeAll(hub) }) else null,
							)
							ShelfRow(hub.items, key = { it.ratingKey }) { item ->
								ShowMenuPoster(
									item = item,
									width = posterWidth,
									onOpen = { navigator?.openItem(item.ratingKey) },
									onMark = { watched ->
										actions.setWatched(watched, item) { now -> browser.patch(item.markedWatched(now)) }
									},
								)
							}
						}
					}
				}
			}
		}
	}
}

/** A poster; on a show a long press offers to mark it watched or unwatched. */
@Composable
private fun ShowMenuPoster(
	item: PlexItem,
	width: Dp,
	onOpen: () -> Unit,
	onMark: (watched: Boolean) -> Unit,
	sort: SectionQuery.Sort? = null,
	clock: DayClock? = null,
) {
	var menu by remember { mutableStateOf(false) }
	val isShow = item.type == MediaType.Show
	Box {
		ItemPoster(
			item = item,
			width = width,
			onClick = onOpen,
			sort = sort,
			clock = clock,
			onLongClick = if (isShow) ({ menu = true }) else null,
			onLongClickLabel = if (isShow) "More options" else null,
		)
		if (menu) {
			ActionMenu(
				expanded = true,
				onDismiss = { menu = false },
				actions = listOf(
					if (item.isWatched) {
						MenuAction(R.drawable.ic_radio_button_unchecked, "Mark Show Unwatched") { onMark(false) }
					} else {
						MenuAction(R.drawable.ic_check_circle, "Mark Show Watched") { onMark(true) }
					},
				),
			)
		}
	}
}

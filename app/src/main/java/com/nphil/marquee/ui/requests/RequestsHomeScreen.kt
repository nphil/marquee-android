package com.nphil.marquee.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.R
import com.nphil.marquee.ui.components.tvFieldNavigation
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrGenre
import com.nphil.marquee.core.SeerrRequest
import com.nphil.marquee.core.SeerrTitle
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.SectionHeader
import com.nphil.marquee.ui.components.ShelfRow
import com.nphil.marquee.ui.components.cardHeight
import com.nphil.marquee.ui.components.pressable
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.pageScroll
import com.nphil.marquee.ui.theme.lucent
import java.text.DateFormat

/** Requests' first page: Discover and My Requests, and a search that takes their place while there is something typed. */
@Composable
internal fun RequestsHomeScreen(container: AppContainer, nav: RequestsNav, onBack: (() -> Unit)? = null) {
	val seerr = container.seerr
	val discover = viewModel { DiscoverModel(seerr) }
	val mine = viewModel { MyRequestsModel(seerr) }
	val search = viewModel { SearchModel(seerr) }
	val text by search.text.collectAsStateWithLifecycle()
	var tab by rememberSaveable { mutableIntStateOf(0) }
	val searching = text.isNotBlank()
	val searchFocus = remember { FocusRequester() }
	BrowseScaffold(
		topBar = {
			TopAppBar(title = { Text("Requests") }, navigationIcon = {
				if (onBack != null) {
					Box(if (BuildConfig.TV_CONTROLS) Modifier.focusProperties { down = searchFocus } else Modifier) {
						BackArrow(onBack)
					}
				}
			})
		},
	) { padding ->
		BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).then(padding.sides())) {
			val wide = maxWidth >= WideBreakpoint
			val bottom = padding.calculateBottomPadding()
			Column(Modifier.fillMaxSize()) {
				SearchField(text, search::edit, Modifier.padding(horizontal = Space.inset, vertical = Space.s2)
					.then(if (BuildConfig.TV_CONTROLS) Modifier.focusRequester(searchFocus) else Modifier))
				if (!searching) {
					SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s2)) {
						listOf("Discover", "My Requests").forEachIndexed { index, label ->
							SegmentedButton(
								selected = tab == index,
								onClick = { tab = index },
								shape = SegmentedButtonDefaults.itemShape(index, 2),
							) { Text(label) }
						}
					}
				}
				when {
					searching -> SearchResults(search, wide, bottom) { nav.title(it.kind, it.tmdbId) }
					tab == 0 -> DiscoverContent(discover, wide, bottom, onBrowse = nav::browse) { nav.title(it.kind, it.tmdbId) }
					else -> MyRequests(mine, bottom) { nav.title(it.kind, it.tmdbId) }
				}
			}
		}
	}
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
	val focus = LocalFocusManager.current
	TextField(
		value = text,
		onValueChange = onChange,
		modifier = modifier.fillMaxWidth().tvFieldNavigation().semantics { contentDescription = "Search movies and series" },
		singleLine = true,
		placeholder = { Text("Search movies and series") },
		leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
		trailingIcon = if (text.isEmpty()) null else ({
			IconButton(onClick = { onChange("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
		}),
		shape = RoundedCornerShape(Radius.control),
		keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
		keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
		colors = TextFieldDefaults.colors(
			focusedContainerColor = lucent.sheet,
			unfocusedContainerColor = lucent.sheet,
			focusedTextColor = lucent.ink,
			unfocusedTextColor = lucent.ink,
			focusedPlaceholderColor = lucent.ink3,
			unfocusedPlaceholderColor = lucent.ink3,
			focusedLeadingIconColor = lucent.ink2,
			unfocusedLeadingIconColor = lucent.ink2,
			focusedTrailingIconColor = lucent.ink2,
			unfocusedTrailingIconColor = lucent.ink2,
			cursorColor = MaterialTheme.colorScheme.primary,
			focusedIndicatorColor = Color.Transparent,
			unfocusedIndicatorColor = Color.Transparent,
		),
	)
}

// MARK: Discover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverContent(
	model: DiscoverModel,
	wide: Boolean,
	bottom: Dp,
	onBrowse: (SeerrFeed) -> Unit,
	onTitle: (SeerrTitle) -> Unit,
) {
	val state by model.state.collectAsStateWithLifecycle()
	var pulled by remember { mutableStateOf(false) }
	// The screen asks when it appears and when the app comes back; rows younger than five minutes are kept.
	LaunchedEffect(model) { model.load() }
	LifecycleEventEffect(Lifecycle.Event.ON_START) { model.load() }
	LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
	val shelves = remember(state.shelves) { state.shelves.filter { !it.isEmpty } }
	val width = posterWidth(wide)
	when {
		state.failed -> CenteredState {
			EmptyState(
				icon = R.drawable.ic_cloud_off,
				title = "Couldn't load Seerr",
				message = state.failure.orEmpty(),
				action = { RetryButton("Try Again") { model.load(force = true) } },
			)
		}
		state.nothing -> CenteredState {
			EmptyState(icon = R.drawable.ic_movie, title = "Nothing to discover", message = "There are no titles to show right now.")
		}
		shelves.isEmpty() -> ShelvesSkeleton(width, Modifier.padding(top = Space.s2))
		else -> PullToRefreshBox(
			isRefreshing = pulled && state.loading,
			onRefresh = {
				pulled = true
				model.load(force = true)
			},
			modifier = Modifier.fillMaxSize(),
		) {
			BrowseList(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(top = Space.s2, bottom = bottom + Space.inset),
				verticalArrangement = Arrangement.spacedBy(Space.s3),
			) {
				items(shelves, key = { it.row.id }) { shelf ->
					Column {
						SectionHeader(shelf.row.title, onSeeAll = { onBrowse(shelf.row.feed) })
						if (shelf.titles.isNotEmpty()) {
							ShelfRow(shelf.titles, key = { it.id }) { title -> SeerrPoster(title, width, onClick = { onTitle(title) }) }
						} else {
							ShelfRow(shelf.genres, key = { it.id }) { genre ->
								GenreCard(genre, if (wide) 240.dp else 200.dp) {
									shelf.row.genres?.let { kind -> onBrowse(SeerrFeed(query = SeerrBrowseQuery(kind = kind, genre = genre.id))) }
								}
							}
						}
					}
				}
			}
		}
	}
}

/** A genre: TMDB's backdrop for it with the name under it, never over it. */
@Composable
private fun GenreCard(genre: SeerrGenre, width: Dp, onClick: () -> Unit) {
	Column(
		modifier = Modifier.width(width).pressable(onClick).semantics(mergeDescendants = true) { contentDescription = genre.name },
		verticalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		TmdbArt(genre.backdropPath, width, cardHeight(width), Modifier.clip(RoundedCornerShape(Radius.card)))
		Text(
			genre.name,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.SemiBold,
			color = lucent.ink,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
	Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

// MARK: My Requests

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyRequests(model: MyRequestsModel, bottom: Dp, onTitle: (SeerrTitle) -> Unit) {
	val state by model.state.collectAsStateWithLifecycle()
	var pulled by remember { mutableStateOf(false) }
	LaunchedEffect(model) { model.loadIfNeeded() }
	LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
	val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
	when {
		!state.loaded && state.failure != null -> CenteredState {
			EmptyState(
				icon = R.drawable.ic_cloud_off,
				title = "Couldn't load Seerr",
				message = state.failure.orEmpty(),
				action = { RetryButton("Try Again", model::load) },
			)
		}
		state.loaded && state.requests.isEmpty() -> CenteredState {
			EmptyState(
				icon = R.drawable.ic_playlist_add,
				title = "No requests yet",
				message = "Movies and series requested from Seerr will appear here.",
			)
		}
		!state.loaded -> ShelvesSkeleton(posterWidth(false), Modifier.padding(top = Space.s2))
		else -> PullToRefreshBox(
			isRefreshing = pulled && state.loading,
			onRefresh = {
				pulled = true
				model.load()
			},
			modifier = Modifier.fillMaxSize(),
		) {
			val grid = rememberLazyGridState()
			LazyVerticalGrid(
				columns = GridCells.Adaptive(360.dp),
				modifier = Modifier.fillMaxSize().pageScroll(grid, bottom = bottom + Space.inset),
				state = grid,
				contentPadding = PaddingValues(start = Space.inset, end = Space.inset, top = Space.s2, bottom = bottom + Space.inset),
				horizontalArrangement = Arrangement.spacedBy(Space.s3),
				verticalArrangement = Arrangement.spacedBy(Space.s3),
			) {
				items(state.requests, key = { it.id }) { request ->
					RequestRow(request, date) { request.title?.let(onTitle) }
				}
			}
		}
	}
}

@Composable
private fun RequestRow(request: SeerrRequest, date: DateFormat, onClick: () -> Unit) {
	val title = request.title
	val line = RequestsRules.requestLine(request)
	val stamp = RequestsRules.requestDate(request, date)
	val name = title?.name ?: "Unavailable title"
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(Radius.row))
			.background(lucent.sheet)
			.then(if (title != null) Modifier.clickable(onClickLabel = "Open $name", onClick = onClick) else Modifier)
			.padding(Space.s3)
			.semantics(mergeDescendants = true) {
				contentDescription = listOfNotNull(name, line, stamp, request.badge?.title).joinToString(", ")
			},
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		TmdbArt(title?.posterPath, 56.dp, 84.dp, Modifier.clip(RoundedCornerShape(Radius.control)))
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(name, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
			line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2) }
			stamp?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink3) }
			request.badge?.let { StatusLine(it) }
		}
	}
}

// MARK: Search

@Composable
private fun SearchResults(model: SearchModel, wide: Boolean, bottom: Dp, onTitle: (SeerrTitle) -> Unit) {
	val state by model.state.collectAsStateWithLifecycle()
	val width = posterWidth(wide)
	when (val shown = state) {
		SearchModel.State.Idle, SearchModel.State.Loading -> ShelvesSkeleton(width, Modifier.padding(top = Space.s2))
		is SearchModel.State.Failed -> CenteredState {
			EmptyState(
				icon = R.drawable.ic_cloud_off,
				title = "Couldn't load Seerr",
				message = shown.message,
				action = { RetryButton("Try Again", model::retry) },
			)
		}
		is SearchModel.State.Found -> if (shown.titles.isEmpty()) {
			CenteredState {
				EmptyState(
					icon = R.drawable.ic_search,
					title = "No results for “${shown.query}”",
					message = "Check the spelling or try another word.",
				)
			}
		} else {
			PosterGrid(shown.titles, width, bottom, onTitle)
		}
	}
}

/** Titles in a grid that fills the width with as many columns as fit. */
@Composable
internal fun PosterGrid(titles: List<SeerrTitle>, width: Dp, bottom: Dp, onTitle: (SeerrTitle) -> Unit) {
	val grid = rememberLazyGridState()
	LazyVerticalGrid(
		columns = GridCells.Adaptive(width),
		modifier = Modifier.fillMaxSize().pageScroll(grid, bottom = bottom + Space.inset),
		state = grid,
		contentPadding = PaddingValues(start = Space.inset, end = Space.inset, top = Space.s2, bottom = bottom + Space.inset),
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
		verticalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		items(titles, key = { it.id }) { title -> SeerrPoster(title, width, onClick = { onTitle(title) }) }
	}
}

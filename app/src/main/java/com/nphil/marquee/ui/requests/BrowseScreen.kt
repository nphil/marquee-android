package com.nphil.marquee.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrGenre
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrPager
import com.nphil.marquee.core.SeerrTitle
import com.nphil.marquee.seerr.SeerrText
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** A list to page through: trending, or a kind with a genre and the View menu's filters. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowseScreen(container: AppContainer, feed: SeerrFeed, nav: RequestsNav) {
	val model = viewModel { BrowseModel(container.seerr, feed) }
	val query by model.query.collectAsStateWithLifecycle()
	val catalogue by model.catalogue.collectAsStateWithLifecycle()
	val options by model.options.collectAsStateWithLifecycle()
	val genres by model.genres.collectAsStateWithLifecycle()
	val picked by model.pickedRegion.collectAsStateWithLifecycle()
	val page by model.pager.state.collectAsStateWithLifecycle()
	var sheet by rememberSaveable { mutableStateOf(false) }
	var pulled by remember { mutableStateOf(false) }
	LaunchedEffect(page.isLoading) { if (!page.isLoading) pulled = false }
	val current = query
	val title = when {
		current == null -> "Trending"
		current.kind == SeerrKind.Movie -> "Movies"
		else -> "Series"
	}
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(title) },
				navigationIcon = {
					IconButton(onClick = nav::back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
				},
				actions = {
					if (current != null) {
						IconButton(onClick = { sheet = true }) {
							Icon(
								painterResource(R.drawable.ic_filter_list),
								contentDescription = if (current.hasMenuFilters) "View, filters on" else "View",
								tint = if (current.hasMenuFilters) MaterialTheme.colorScheme.primary else lucent.ink,
							)
						}
					}
				},
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).then(padding.sides())) {
			val wide = maxWidth >= WideBreakpoint
			val bottom = padding.calculateBottomPadding()
			val cards = catalogue
			val filters: @Composable (Modifier) -> Unit = { modifier ->
				if (current != null && cards != null) {
					LaunchedEffect(Unit) { model.loadOptions() }
					BrowseFilters(
						query = current,
						catalogue = cards,
						options = options,
						region = remember(current, picked, options.regions) { model.region(current) },
						onEdit = model::edit,
						onRegion = model::setRegion,
						onStreaming = model::setStreaming,
						onClear = model::clearFilters,
						modifier = modifier,
					)
				}
			}
			Row(Modifier.fillMaxSize()) {
				Column(Modifier.weight(1f)) {
					if (current != null) {
						KindSwitch(current.kind, model::setKind)
						if (genres.isNotEmpty()) GenreChips(current, genres, model::edit)
						val providerName = options.providers.firstOrNull { it.id == current.streaming?.provider }?.name
						val chips = remember(current, cards, options.languages, providerName) {
							if (cards == null) emptyList() else RequestsRules.chips(current, cards, options.languages, providerName)
						}
						if (chips.isNotEmpty()) {
							LazyRow(
								contentPadding = PaddingValues(horizontal = Space.inset),
								horizontalArrangement = Arrangement.spacedBy(Space.s2),
							) {
								items(chips, key = { it.filter }) { chip ->
									AssistChip(
										onClick = { model.edit { RequestsRules.without(it, chip.filter) } },
										label = { Text(chip.text) },
										trailingIcon = {
											Icon(
												painterResource(R.drawable.ic_close),
												contentDescription = "Remove ${chip.text}",
												modifier = Modifier.size(18.dp),
											)
										},
									)
								}
							}
						}
					}
					PullToRefreshBox(
						isRefreshing = pulled && page.isLoading,
						onRefresh = {
							pulled = true
							model.refresh()
						},
						modifier = Modifier.weight(1f).fillMaxWidth(),
					) {
						BrowseList(model, page, current, wide, bottom, onTitle = { nav.title(it.kind, it.tmdbId) })
					}
				}
				if (wide && current != null) {
					filters(Modifier.width(PANEL_WIDTH).verticalScroll(rememberScrollState()).padding(bottom = bottom + Space.inset))
				}
			}
			if (sheet && !wide) {
				ModalBottomSheet(
					onDismissRequest = { sheet = false },
					sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
					containerColor = lucent.canvas,
				) {
					filters(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.s6))
				}
			}
		}
	}
}

@Composable
private fun KindSwitch(kind: SeerrKind, onChange: (SeerrKind) -> Unit) {
	SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s2)) {
		listOf(SeerrKind.Movie to "Movies", SeerrKind.Tv to "Series").forEachIndexed { index, (value, label) ->
			SegmentedButton(
				selected = kind == value,
				onClick = { onChange(value) },
				shape = SegmentedButtonDefaults.itemShape(index, 2),
			) { Text(label) }
		}
	}
}

@Composable
private fun GenreChips(query: SeerrBrowseQuery, genres: List<SeerrGenre>, onEdit: ((SeerrBrowseQuery) -> SeerrBrowseQuery) -> Unit) {
	LazyRow(
		contentPadding = PaddingValues(horizontal = Space.inset, vertical = Space.s1),
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		items(genres, key = { it.id }) { genre ->
			val selected = query.genre == genre.id
			FilterChip(
				selected = selected,
				onClick = { onEdit { it.copy(genre = if (selected) null else genre.id) } },
				label = { Text(genre.name) },
			)
		}
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowseList(
	model: BrowseModel,
	page: SeerrPager.State,
	query: SeerrBrowseQuery?,
	wide: Boolean,
	bottom: Dp,
	onTitle: (SeerrTitle) -> Unit,
) {
	val width = posterWidth(wide)
	val failure = page.failure
	when {
		failure != null && page.items.isEmpty() -> Centered {
			EmptyState(
				icon = R.drawable.ic_cloud_off,
				title = "Couldn't load Seerr",
				message = SeerrText.failure(failure),
				action = { RetryButton("Try Again", model::reload) },
			)
		}
		!page.hasLoaded -> LazyVerticalGrid(
			columns = GridCells.Adaptive(width),
			modifier = Modifier.fillMaxSize().semantics { contentDescription = "Loading" },
			contentPadding = PaddingValues(start = Space.inset, end = Space.inset, top = Space.s2, bottom = bottom + Space.inset),
			horizontalArrangement = Arrangement.spacedBy(Space.s3),
			userScrollEnabled = false,
			verticalArrangement = Arrangement.spacedBy(Space.s4),
		) {
			items(PLACEHOLDERS, key = { it }) { PosterPlaceholder(width) }
		}
		page.items.isEmpty() -> Centered {
			if (query?.hasMenuFilters == true) {
				EmptyState(
					icon = R.drawable.ic_filter_list,
					title = "No matches",
					message = "Nothing here fits these filters.",
					action = { TextAction("Clear Filters", model::clearFilters) },
				)
			} else {
				EmptyState(icon = R.drawable.ic_movie, title = "Nothing here", message = "Seerr lists no titles here.")
			}
		}
		else -> LazyVerticalGrid(
			columns = GridCells.Adaptive(width),
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(start = Space.inset, end = Space.inset, top = Space.s2, bottom = bottom + Space.inset),
			horizontalArrangement = Arrangement.spacedBy(Space.s3),
			verticalArrangement = Arrangement.spacedBy(Space.s4),
		) {
			items(page.items, key = { it.id }) { title ->
				SeerrPoster(title, width, onClick = { onTitle(title) })
				LaunchedEffect(title.id) { model.loadMoreIfNeeded(title.id) }
			}
			if (page.moreFailed) {
				item(key = "moreFailed", span = { GridItemSpan(maxLineSpan) }) {
					Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
						Text("Couldn't load more", color = lucent.ink2, style = MaterialTheme.typography.bodyLarge)
						RetryButton("Try Again", model::loadMore)
					}
				}
			} else if (page.hasMore) {
				item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator(Modifier.size(40.dp)) }
				}
			}
		}
	}
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
	Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

private val PLACEHOLDERS = (0 until 18).toList()
private val PANEL_WIDTH = 360.dp

package com.nphil.marquee.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.SearchModel
import com.nphil.marquee.browse.SearchScope
import com.nphil.marquee.browse.SearchUi
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.kindLine
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.spokenSummary
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.PosterGridMetrics
import com.nphil.marquee.ui.browse.RowsSkeleton
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.EpisodeCard
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.SectionHeader
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.posterSharedElement
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * Search (DESIGN §8.2): a field, the kinds of title to cover, then the server's groups for what is typed. With nothing
 * typed it lists the profile's recent searches, which a submitted search or an opened result adds to.
 * A recent search swipes away. [onBack] shows a back arrow when the page was opened from a menu rather than as a tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(env: BrowseEnvironment, place: BrowsePlace, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
	val model: SearchModel = viewModel(key = "search:${place.key}") { SearchModel(env, place) }
	val ui by model.ui.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val focus = LocalFocusManager.current
	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text("Search") },
				navigationIcon = { if (onBack != null) BackArrow(onBack) },
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		Column(Modifier.padding(padding).fillMaxSize()) {
			SearchField(
				text = ui.query,
				onText = model::type,
				onSubmit = {
					model.keep(ui.query)
					focus.clearFocus()
				},
				modifier = Modifier.fillMaxWidth().padding(horizontal = Space.inset),
			)
			ScopeChips(ui.scope, model::choose)
			val query = ui.query.trim()
			val results = ui.results
			when {
				query.isEmpty() -> Recents(ui.recents, onPick = model::type, onForget = model::forget, onClear = model::clearRecents)
				results.value == null && results.failure != null -> Centered {
					EmptyState(R.drawable.ic_search, "Couldn't search", "Check the connection and try again.") {
						RetryButton("Try again", model::retry)
					}
				}
				results.value == null -> RowsSkeleton(Modifier.padding(top = Space.s3))
				else -> Results(ui) { item ->
					model.keep(ui.query)
					navigator?.openItem(item.ratingKey)
				}
			}
		}
	}
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
	TextField(
		value = text,
		onValueChange = onText,
		modifier = modifier,
		placeholder = { Text("Movies, shows and episodes") },
		leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
		trailingIcon = {
			if (text.isNotEmpty()) {
				IconButton(onClick = { onText("") }) {
					Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
				}
			}
		},
		singleLine = true,
		shape = RoundedCornerShape(Radius.control),
		colors = TextFieldDefaults.colors(
			focusedContainerColor = lucent.sheet,
			unfocusedContainerColor = lucent.sheet,
			focusedIndicatorColor = Color.Transparent,
			unfocusedIndicatorColor = Color.Transparent,
		),
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
		keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
	)
}

@Composable
private fun ScopeChips(selected: SearchScope, onSelect: (SearchScope) -> Unit) {
	Row(
		Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.inset, vertical = Space.s2),
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		for (scope in SearchScope.entries) {
			FilterChip(selected = scope == selected, onClick = { onSelect(scope) }, label = { Text(scope.label) })
		}
	}
}

@Composable
private fun Recents(recents: List<String>, onPick: (String) -> Unit, onForget: (String) -> Unit, onClear: () -> Unit) {
	if (recents.isEmpty()) {
		Centered { EmptyState(R.drawable.ic_search, "Search your library", "Find movies, shows and episodes.") }
		return
	}
	LazyColumn(Modifier.fillMaxSize()) {
		item(key = "header") {
			Row(Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.s2), verticalAlignment = Alignment.CenterVertically) {
				Text("Recent Searches", style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
				TextAction("Clear", onClear)
			}
		}
		items(recents, key = { it }) { search ->
			RecentRow(search, onPick = { onPick(search) }, onForget = { onForget(search) }, modifier = Modifier.animateItem())
		}
	}
}

/** One recent search: a tap searches for it, a swipe toward the start takes it off the list (TalkBack gets a "Remove" action). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentRow(search: String, onPick: () -> Unit, onForget: () -> Unit, modifier: Modifier = Modifier) {
	val state = rememberSwipeToDismissBoxState(
		confirmValueChange = { target -> (target == SwipeToDismissBoxValue.EndToStart).also { if (it) onForget() } },
	)
	SwipeToDismissBox(
		state = state,
		backgroundContent = {
			Box(Modifier.fillMaxSize().background(lucent.danger).padding(end = Space.inset), contentAlignment = Alignment.CenterEnd) {
				Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = lucent.canvas)
			}
		},
		modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("Remove $search") { onForget(); true }) },
		enableDismissFromStartToEnd = false,
	) {
		Row(
			Modifier.fillMaxWidth().background(lucent.canvas).heightIn(min = Size.rowMin)
				.clickable(onClickLabel = "Search for $search", onClick = onPick).padding(horizontal = Space.inset),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = lucent.ink3)
			Text(
				search,
				style = MaterialTheme.typography.bodyLarge,
				color = lucent.ink,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f).padding(start = Space.s3),
			)
		}
	}
}

@Composable
private fun Results(ui: SearchUi, onOpen: (PlexItem) -> Unit) {
	val groups = ui.groups
	if (groups.isEmpty()) {
		Centered { EmptyState(R.drawable.ic_search, "No results for “${ui.query.trim()}”", "Check the spelling or try a different search.") }
		return
	}
	BoxWithConstraints(Modifier.fillMaxSize()) {
		if (maxWidth < WideResultsMin) {
			ResultList(groups, onOpen)
		} else {
			ResultGrids(groups, maxWidth - Space.inset * 2, onOpen)
		}
	}
}

/** The width from which results fill the screen in grids, as Library's posters do; narrower, they stay a list. */
private val WideResultsMin = 600.dp

/** The least width of an episode card in the grid: wide enough for a 16:9 still and a two-line title. */
private val EpisodeCardMin = 280.dp

@Composable
private fun ResultList(groups: List<PlexHub>, onOpen: (PlexItem) -> Unit) {
	LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.s6)) {
		groups.forEachIndexed { index, hub ->
			item(key = "hub:$index") {
				Text(
					hub.title,
					style = MaterialTheme.typography.titleMedium,
					color = lucent.ink,
					modifier = Modifier.padding(horizontal = Space.inset, vertical = Space.s2),
				)
			}
			items(hub.items, key = { "$index/${it.ratingKey}" }) { item -> ResultRow(item) { onOpen(item) } }
		}
	}
}

/** One line of the wide results: a header across the width, or one row of a group's grid of posters or episode cards. */
private sealed interface ResultEntry {
	val key: String

	data class Header(override val key: String, val title: String) : ResultEntry
	data class Posters(override val key: String, val items: List<PlexItem>) : ResultEntry
	data class Episodes(override val key: String, val items: List<PlexItem>) : ResultEntry
}

/** The groups as header and grid-row entries: titles as posters, episodes as cards, each in as many columns as fit. */
private fun resultEntries(groups: List<PlexHub>, posterColumns: Int, episodeColumns: Int): List<ResultEntry> = buildList {
	groups.forEachIndexed { index, hub ->
		add(ResultEntry.Header("hub:$index", hub.title))
		val (episodes, titles) = hub.items.partition { it.type == MediaType.Episode }
		titles.chunked(posterColumns).forEach { row -> add(ResultEntry.Posters("p:$index/${row.first().ratingKey}", row)) }
		episodes.chunked(episodeColumns).forEach { row -> add(ResultEntry.Episodes("e:$index/${row.first().ratingKey}", row)) }
	}
}

/**
 * The results on a wide screen, like Library's grid: each group's titles as posters and its episodes as cards, filling
 * [width], under headers that span it. Rows of a grid are the list's items, so only the rows on screen are composed.
 */
@Composable
private fun ResultGrids(groups: List<PlexHub>, width: Dp, onOpen: (PlexItem) -> Unit) {
	val fontScale = LocalDensity.current.fontScale
	val posters = PosterGridMetrics.of(width, fontScale)
	val cards = PosterGridMetrics.of(width, fontScale, minCell = EpisodeCardMin)
	val entries = remember(groups, posters.columns, cards.columns) { resultEntries(groups, posters.columns, cards.columns) }
	LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.s1, bottom = Space.s7)) {
		items(entries, key = { it.key }, contentType = { it::class }) { entry ->
			when (entry) {
				is ResultEntry.Header -> SectionHeader(entry.title)
				is ResultEntry.Posters -> GridRow(posters.gutter) {
					for (item in entry.items) {
						ItemPoster(item, posters.cellWidth, onClick = { onOpen(item) }, line = item.kindLine, reserveLine = true)
					}
				}
				is ResultEntry.Episodes -> GridRow(cards.gutter) {
					for (item in entry.items) {
						EpisodeCard(
							title = item.title,
							artPath = item.thumb ?: item.art ?: item.grandparentArt,
							width = cards.cellWidth,
							line = item.kindLine,
							progress = item.progress,
							description = item.spokenSummary,
							onClick = { onOpen(item) },
							artModifier = Modifier.posterSharedElement(item.ratingKey),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun GridRow(gutter: Dp, content: @Composable () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.inset, bottom = Space.s5),
		horizontalArrangement = Arrangement.spacedBy(gutter),
		verticalAlignment = Alignment.Top,
	) { content() }
}

@Composable
private fun ResultRow(item: PlexItem, onClick: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${item.title}", onClick = onClick).padding(horizontal = Space.inset, vertical = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
	) {
		PlexArt(item.posterPath, 56.dp, 84.dp, Modifier.clip(RoundedCornerShape(Radius.control)))
		Column(Modifier.weight(1f).padding(start = Space.s3)) {
			Text(item.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Text(item.kindLine, style = MaterialTheme.typography.bodySmall, color = lucent.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

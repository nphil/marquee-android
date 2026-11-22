package com.nphil.marquee.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.SearchModel
import com.nphil.marquee.browse.SearchScope
import com.nphil.marquee.browse.SearchUi
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.kindLine
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.browse.RowsSkeleton
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * Search (DESIGN §8.2): a field, the kinds of title to cover, then the server's groups for what is typed. With nothing
 * typed it lists the profile's recent searches, which a submitted search or an opened result adds to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(env: BrowseEnvironment, place: BrowsePlace, modifier: Modifier = Modifier) {
	val model: SearchModel = viewModel(key = "search:${place.key}") { SearchModel(env, place) }
	val ui by model.ui.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	val focus = LocalFocusManager.current
	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text("Search") },
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
			Row(
				Modifier.fillMaxWidth().heightIn(min = Size.rowMin).clickable(onClickLabel = "Search for $search") { onPick(search) }.padding(start = Space.inset),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = lucent.ink3)
				Text(
					search,
					style = MaterialTheme.typography.bodyLarge,
					color = lucent.ink,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f).padding(horizontal = Space.s3),
				)
				IconButton(onClick = { onForget(search) }) {
					Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $search", tint = lucent.ink3)
				}
			}
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

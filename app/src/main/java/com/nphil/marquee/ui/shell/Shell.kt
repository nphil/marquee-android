package com.nphil.marquee.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.TabLayout
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.ui.home.HomeScreen
import com.nphil.marquee.ui.library.LibraryScreen
import com.nphil.marquee.ui.requests.RequestsDestination
import com.nphil.marquee.ui.search.SearchScreen
import com.nphil.marquee.ui.settings.SettingsScreen
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.watchlist.WatchlistScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Windows at least this wide (dp) get the sidebar; narrower ones keep the bottom bar. */
private const val WIDE_WIDTH_DP = 600

private enum class Page(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
	Home("Home", R.drawable.ic_home, R.drawable.ic_home_filled),
	Library("Library", R.drawable.ic_video_library, R.drawable.ic_video_library_filled),
	Watchlist("Watchlist", R.drawable.ic_bookmark, R.drawable.ic_bookmark_filled),
	Requests("Requests", R.drawable.ic_playlist_add, R.drawable.ic_playlist_add),
	Search("Search", R.drawable.ic_search, R.drawable.ic_search),
	Settings("Settings", R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

private fun Destination.page(): Page? = when (this) {
	Destination.Home -> Page.Home
	Destination.Library -> Page.Library
	Destination.Watchlist -> Page.Watchlist
	Destination.Requests -> Page.Requests
	Destination.Search -> Page.Search
	else -> null
}

private fun Page.destination() = SidebarDestination(name, label, icon, selectedIcon)

/** What the person chose for the sidebar before; [expanded] is null while they have not chosen. */
private class RememberedSidebar(val expanded: Boolean?)

/**
 * The signed-in app: Home, Library and the rest in a bottom bar on a phone, in the sidebar on a tablet. The sidebar
 * opens by default when the window is wide, and keeps the person's choice after that. On a phone Settings is the
 * button on Home's bar.
 */
@Composable
fun Shell(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
	val env = container.browse
	val remembered by produceState<RememberedSidebar?>(null, env) {
		value = RememberedSidebar(env.preferences.sidebarExpanded.first())
	}
	val place by env.source.place.collectAsStateWithLifecycle()
	val seerr by container.seerr.enabled.collectAsStateWithLifecycle()
	val sidebar = remembered
	val where = place
	if (sidebar == null || where == null) {
		Box(Modifier.fillMaxSize().background(lucent.canvas))
		return
	}
	ShellPages(container, env, where, sidebar.expanded, seerr, choice, onTheme)
}

@Composable
private fun ShellPages(
	container: AppContainer,
	env: BrowseEnvironment,
	place: BrowsePlace,
	rememberedExpanded: Boolean?,
	seerr: Boolean,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
) {
	val wide = LocalConfiguration.current.screenWidthDp >= WIDE_WIDTH_DP
	val tabs = remember(seerr) {
		val available = setOfNotNull(
			Destination.Home,
			Destination.Library,
			Destination.Watchlist,
			Destination.Search,
			Destination.Requests.takeIf { seerr },
		)
		TabLayout.standard.tabs(available).mapNotNull { it.page() }
	}
	var selected by rememberSaveable { mutableStateOf(Page.Home) }
	val page = if (selected == Page.Settings || selected in tabs) selected else Page.Home
	BackHandler(enabled = page != Page.Home) { selected = Page.Home }
	LaunchedEffect(page) { DiagScreen.tab(page.label) }
	val pages: @Composable (Modifier) -> Unit = { modifier ->
		Pages(page, tabs, wide, container, env, place, choice, onTheme, onPage = { selected = it }, modifier)
	}

	if (!wide) {
		Scaffold(
			bottomBar = {
				NavigationBar {
					for (item in tabs) {
						val on = page == item
						NavigationBarItem(
							selected = on,
							onClick = { selected = item },
							icon = { Icon(painterResource(if (on) item.selectedIcon else item.icon), contentDescription = null) },
							label = { Text(item.label) },
						)
					}
				}
			},
			containerColor = lucent.canvas,
			contentWindowInsets = WindowInsets(0, 0, 0, 0),
		) { padding ->
			pages(Modifier.padding(padding).consumeWindowInsets(padding))
		}
		return
	}

	var expanded by rememberSaveable { mutableStateOf(rememberedExpanded ?: true) }
	val scope = rememberCoroutineScope()
	val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
	Row(Modifier.fillMaxSize().background(lucent.canvas)) {
		MarqueeSidebar(
			destinations = tabs.map { it.destination() },
			selected = page.name,
			onSelect = { selected = Page.valueOf(it) },
			expanded = expanded,
			onToggle = {
				expanded = !expanded
				scope.launch { env.preferences.setSidebarExpanded(expanded) }
			},
			footer = listOf(Page.Settings.destination()),
		)
		pages(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(startInsets))
	}
}

@Composable
private fun Pages(
	page: Page,
	tabs: List<Page>,
	wide: Boolean,
	container: AppContainer,
	env: BrowseEnvironment,
	place: BrowsePlace,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	onPage: (Page) -> Unit,
	modifier: Modifier,
) {
	val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	Crossfade(targetState = page, modifier = modifier, animationSpec = effects, label = "page") { shown ->
		when (shown) {
			Page.Home -> HomeScreen(
				env = env,
				place = place,
				watchlistShelf = Page.Watchlist !in tabs,
				onOpenWatchlist = { onPage(Page.Watchlist) },
				onSignIn = container.session::signOut,
				trailing = {
					if (!wide) {
						IconButton(onClick = { onPage(Page.Settings) }) {
							Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
						}
					}
				},
			)
			Page.Library -> LibraryScreen(env, place, onSignIn = container.session::signOut)
			Page.Watchlist -> WatchlistScreen(env, place, onSignIn = container.session::signOut)
			Page.Requests -> RequestsDestination(container)
			Page.Search -> SearchScreen(env, place)
			Page.Settings -> SettingsScreen(container.session, container.themes, choice, onTheme)
		}
	}
}

package com.nphil.marquee.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.TabBarBehavior
import com.nphil.marquee.core.TabLayout
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.bigscreen.GlassesRemoteButton
import com.nphil.marquee.link.LinkedPhone
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.downloads.DownloadsScreen
import com.nphil.marquee.ui.home.HomeScreen
import com.nphil.marquee.ui.library.LibraryScreen
import com.nphil.marquee.ui.link.LinkEntryIcon
import com.nphil.marquee.ui.live.LiveGuideDestination
import com.nphil.marquee.ui.player.LocalDockAnchor
import com.nphil.marquee.ui.player.LocalDockRoom
import com.nphil.marquee.ui.requests.RequestsDestination
import com.nphil.marquee.ui.search.SearchScreen
import com.nphil.marquee.ui.settings.SettingsScreen
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.watchlist.WatchlistScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Windows at least this wide (dp) get the sidebar; narrower ones keep the bottom bar. */
private const val WIDE_WIDTH_DP = 600

private fun Destination.sidebarEntry() = SidebarDestination(name, title, tabIcon(false), tabIcon(true))

/** What the person chose for the sidebar before; [expanded] is null while they have not chosen. */
private class RememberedSidebar(val expanded: Boolean?)

/** What the content area shows: a tab or Settings, or a [place] opened over it. Equal values are the same screen. */
private data class Showing(val page: String, val place: Destination?) {
	val key: String get() = "$page/${place?.name}"
}

/**
 * The signed-in app: the tabs the person chose in a bottom bar on a phone, in the sidebar on a tablet. A destination
 * that is not a tab opens from a Places menu over the page. The sidebar opens by default when the window is wide, and
 * keeps the person's choice after that. On a phone Settings is the button on Home's bar.
 */
@Composable
fun Shell(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
	val env = container.browse
	val remembered by produceState<RememberedSidebar?>(null, env) {
		value = RememberedSidebar(env.preferences.sidebarExpanded.first())
	}
	val place by env.source.place.collectAsStateWithLifecycle()
	val seerr by container.seerr.enabled.collectAsStateWithLifecycle()
	val layout by env.preferences.tabLayout.collectAsStateWithLifecycle(initialValue = null as TabLayout?)
	val behavior by env.preferences.tabBarBehavior.collectAsStateWithLifecycle(initialValue = TabBarBehavior.Always)
	val sidebar = remembered
	val where = place
	val tabLayout = layout
	if (sidebar == null || where == null || tabLayout == null) {
		Box(Modifier.fillMaxSize().background(lucent.canvas))
		return
	}
	ShellPages(container, env, where, sidebar.expanded, tabLayout, seerr, behavior, choice, onTheme)
}

@Composable
private fun ShellPages(
	container: AppContainer,
	env: BrowseEnvironment,
	place: BrowsePlace,
	rememberedExpanded: Boolean?,
	layout: TabLayout,
	seerr: Boolean,
	behavior: TabBarBehavior,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
	val wide = maxWidth >= WIDE_WIDTH_DP.dp
	val remote = container.tvRemote.state.collectAsStateWithLifecycle()
	val remoteEnabled by remember(remote) { derivedStateOf { remote.value.enabled } }
	val link = container.link.state.collectAsStateWithLifecycle()
	val controllable by remember(link) { derivedStateOf { link.value.controllable } }
	val session by container.session.state.collectAsStateWithLifecycle()
	val settingsProfile = session.user
	val glasses = container.glasses
	val bigScreen = if (glasses.isConnected) remember(glasses.remoteTitle, glasses.symbol) { BigScreenEntry(glasses.remoteTitle, glasses.symbol, glasses::showRemote) } else null
	val available = remember(seerr) { TabLayout.available(seerr) }
	val tabs = remember(layout, available) { layout.tabs(available) }
	val others = remember(layout, tabs, available) { placesOutsideTabs(layout, tabs, available) }
	var savedPage by rememberSaveable { mutableStateOf(Destination.Home.name) }
	var savedPlace by rememberSaveable { mutableStateOf<String?>(null) }
	val page = selectedPage(savedPage, tabs)
	val opened = openedPlace(savedPlace, others)
	val select: (String) -> Unit = { key ->
		savedPage = key
		savedPlace = null
	}
	val places = remember(others, tabs, available) {
		Places(others) { destination ->
			when (val route = routePlace(destination, tabs, available)) {
				is PlaceRoute.Switch -> {
					savedPage = route.destination.name
					savedPlace = null
				}
				is PlaceRoute.Open -> savedPlace = route.destination.name
				PlaceRoute.Ignore -> Unit
			}
		}
	}
	val navigator = LocalNavigator.current
	LaunchedEffect(glasses, navigator) {
		glasses.settingsRequests.collect {
			navigator?.toRoot()
			select(SETTINGS_KEY)
		}
	}
	// A place that stopped being one (the layout was edited) must not come back if the layout is edited again.
	LaunchedEffect(opened) { if (opened == null) savedPlace = null }
	BackHandler(enabled = page != Destination.Home.name) { select(Destination.Home.name) }
	BackHandler(enabled = opened != null) { savedPlace = null }
	val showing = Showing(page, opened)
	LaunchedEffect(showing) { DiagScreen.tab(opened?.title ?: if (page == SETTINGS_KEY) "Settings" else Destination.valueOf(page).title) }
	// The player's bar sits right under the pages, so the pages say where they end, and leave room at their foot while it shows.
	val anchor = LocalDockAnchor.current
	val room = LocalDockRoom.current
	DisposableEffect(anchor) { onDispose { anchor.pages = null } }

	val pages: @Composable (Modifier) -> Unit = { modifier ->
		Pages(showing, tabs, wide, container, env, place, places, choice, onTheme, settingsProfile, controllable, onSettings = { select(SETTINGS_KEY) }, onBack = { savedPlace = null }, modifier)
	}

	if (!wide) {
		val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
		val scroll = remember { BarScroll(settle) }
		val hide = behavior == TabBarBehavior.HideOnScroll
		SideEffect { scroll.enabled = hide }
		// The bar rides with the tab bar as it slides away, but stops above the system's navigation bar.
		val navigation = WindowInsets.navigationBars.getBottom(LocalDensity.current)
		SideEffect { anchor.hidden = { scroll.hidden.coerceAtMost(scroll.limit - navigation).coerceAtLeast(0f) } }
		// A page that arrives, and a bar that must stay put, always find the bar shown.
		LaunchedEffect(showing, hide) { scroll.show() }
		Scaffold(
			bottomBar = {
				NavigationBar(
					Modifier.layout { measurable, constraints ->
						val bar = measurable.measure(constraints)
						scroll.limit = bar.height.toFloat()
						// The slot shrinks with the hidden part, so the page grows into it as the bar slides away.
						layout(bar.width, bar.height - scroll.hidden.roundToInt()) { bar.place(0, 0) }
					},
				) {
					for (item in tabs) {
						val on = page == item.name
						NavigationBarItem(
							selected = on,
							onClick = { select(item.name) },
							icon = { Icon(painterResource(item.tabIcon(on)), contentDescription = null) },
							label = { Text(item.title) },
						)
					}
				}
			},
			containerColor = lucent.canvas,
			contentWindowInsets = WindowInsets(0, 0, 0, 0),
		) { padding ->
			// Where the pages end is kept with the tab bar up; the bar adds the slide itself, so it keeps pace with no lag.
			pages(
				Modifier.padding(padding).padding(bottom = room).fillMaxSize()
					.onGloballyPositioned { anchor.pages = it.boundsInWindow().translate(0f, -scroll.hidden) }
					.consumeWindowInsets(padding).nestedScroll(scroll),
			)
		}
		return@BoxWithConstraints
	}

	var expanded by rememberSaveable { mutableStateOf(rememberedExpanded ?: true) }
	val scope = rememberCoroutineScope()
	val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
	val shellWidth = constraints.maxWidth
	// While the player shows, the page ends above the navigation bar, where the player's bar goes, and leaves it to the bar.
	val docked = room > 0.dp
	val navigation = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
	val foot = if (docked) room + with(LocalDensity.current) { navigation.getBottom(this).toDp() } else 0.dp
	SideEffect { anchor.hidden = { 0f } }
	Row(Modifier.fillMaxSize().background(lucent.canvas)) {
		MarqueeSidebar(
			destinations = tabs.map { it.sidebarEntry() },
			selected = page,
			onSelect = select,
			expanded = expanded,
			onToggle = {
				expanded = !expanded
				scope.launch { env.preferences.setSidebarExpanded(expanded) }
			},
			settingsProfile = settingsProfile,
			onRemote = if (remoteEnabled) container.tvRemote::open else null,
			bigScreen = bigScreen,
			onLink = controllable?.let { phone -> { container.link.control(phone.id) } },
			linkLabel = "Control ${controllable?.name.orEmpty()}",
		)
		pages(Modifier.weight(1f).fillMaxHeight().padding(bottom = foot).onGloballyPositioned { anchor.pages = it.boundsInWindow() }.clipToBounds().layout { child, available ->
			val full = shellWidth - startInsets.getLeft(this, layoutDirection) - startInsets.getRight(this, layoutDirection)
			// Slide the wide page behind the trailing clip. Reflow posters once, not at each spring step.
			val narrow = (full - ExpandedWidth.roundToPx()).coerceAtLeast(0)
			val width = if (expanded && available.maxWidth <= narrow) available.maxWidth
				else (full - CollapsedWidth.roundToPx()).coerceAtLeast(available.maxWidth)
			val page = child.measure(available.copy(minWidth = width, maxWidth = width))
			layout(available.maxWidth, page.height) { page.placeRelative(0, 0) }
		}.consumeWindowInsets(startInsets).then(if (docked) Modifier.consumeWindowInsets(navigation) else Modifier))
	}
}

@Composable
private fun Pages(
	showing: Showing,
	tabs: List<Destination>,
	wide: Boolean,
	container: AppContainer,
	env: BrowseEnvironment,
	place: BrowsePlace,
	places: Places,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	settingsProfile: PlexHomeUser?,
	controllable: LinkedPhone?,
	onSettings: () -> Unit,
	onBack: () -> Unit,
	modifier: Modifier,
) {
	val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	// Each screen keeps its scroll position and chosen options while another one is shown over it.
	val saveable = rememberSaveableStateHolder()
	Crossfade(targetState = showing, modifier = modifier, animationSpec = effects, label = "page") { shown ->
		saveable.SaveableStateProvider(shown.key) {
			if (shown.place == null && shown.page == SETTINGS_KEY) {
				SettingsScreen(container.session, container.themes, choice, onTheme, onDownloads = { places.open(Destination.Downloads) })
				return@SaveableStateProvider
			}
			val destination = shown.place ?: Destination.valueOf(shown.page)
			val back = if (shown.place != null) onBack else null
			when (destination) {
				Destination.Home -> HomeScreen(
					env = env,
					place = place,
					watchlistShelf = Destination.Watchlist !in tabs,
					onOpenWatchlist = { places.open(Destination.Watchlist) },
					onSignIn = container.session::signOut,
					places = places,
					trailing = {
						if (!wide) {
							if (container.glasses.isConnected) GlassesRemoteButton(container.glasses)
							controllable?.let { phone -> LinkEntryIcon(phone.name, onClick = { container.link.control(phone.id) }) }
							CompactSettingsButton(settingsProfile, onClick = onSettings)
						}
					},
				)
				Destination.Library -> LibraryScreen(env, place, onSignIn = container.session::signOut, places = places, onBack = back)
				Destination.Watchlist -> WatchlistScreen(env, place, onSignIn = container.session::signOut, onBack = back)
				Destination.Requests -> RequestsDestination(container, onBack = back)
				Destination.Search -> SearchScreen(env, place, onBack = back)
				Destination.Downloads -> DownloadsScreen(container.downloads, onBack = back)
				Destination.LiveTv -> LiveGuideDestination(container, place, onBack = back)
			}
		}
	}
}

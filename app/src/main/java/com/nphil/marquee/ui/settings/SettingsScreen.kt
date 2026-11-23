package com.nphil.marquee.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.SubtitleService
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.playback.rememberPlaybackPrefs
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.cast.CastProvider
import com.nphil.marquee.ui.browse.LocalPosterLabels
import com.nphil.marquee.ui.cast.CastSettingsPage
import com.nphil.marquee.ui.components.Avatar
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.IconBadge
import com.nphil.marquee.ui.components.plusInset
import com.nphil.marquee.ui.diagnostics.DiagnosticLogsPage
import com.nphil.marquee.ui.downloads.DownloadSettingsPage
import com.nphil.marquee.ui.profiles.ProfilesPage
import com.nphil.marquee.ui.servers.ServerList
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

enum class SettingsPage(val title: String) {
	Profile("Profiles"),
	Server("Servers"),
	Appearance("Theme"),
	TabBar("Tab Bar"),
	HeaderStyle("Header Style"),
	FavoriteLibraries("Favorite Libraries"),
	UnderPosters("Under Posters"),
	VideoPlayback("Video & Playback"),
	Downloads("Downloads"),
	Seerr("Seerr"),
	Bazarr("Bazarr"),
	SpeechDock("SpeechDock"),
	HomeLabber("HomeLabber Quality Map"),
	TvRemote("LumaTV Remote"),
	BigScreen("XR Glasses & TV"),
	Chromecast("Chromecast"),
	AirPlay("AirPlay"),
	LinkedPhones("Linked Phones"),
	DiagnosticLogs("Diagnostic Logs"),
	Licences("Licences"),
}

/**
 * Settings as a list and its pages: side by side where the window is wide enough, one at a time with
 * predictive back where it is not. Wide, the page beside the list starts on the theme.
 */
@Composable
fun SettingsScreen(
	session: AppSession,
	themes: Themes,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	onDownloads: () -> Unit,
) {
	val navigator = rememberListDetailPaneScaffoldNavigator<SettingsPage>()
	val scope = rememberCoroutineScope()
	var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
	val twoPane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
		navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
	val page = navigator.currentDestination?.contentKey ?: SettingsPage.Appearance
	val shown = if (twoPane) page else navigator.currentDestination?.contentKey
	LaunchedEffect(shown) { DiagScreen.path(shown?.title) }

	NavigableListDetailPaneScaffold(
		navigator = navigator,
		listPane = {
			AnimatedPane {
				SettingsList(
					session = session,
					themes = themes,
					choice = choice,
					onTheme = onTheme,
					selected = page.takeIf { twoPane },
					onOpen = { next -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, next) } },
					onSignOut = { confirmingSignOut = true },
				)
			}
		},
		detailPane = {
			AnimatedPane {
				SettingsDetail(
					page = page,
					session = session,
					themes = themes,
					choice = choice,
					onTheme = onTheme,
					onDownloads = onDownloads,
					onBack = if (twoPane) null else ({ scope.launch { navigator.navigateBack() } }),
				)
			}
		},
	)
	if (confirmingSignOut) SignOutDialog(onConfirm = session::signOut, onDismiss = { confirmingSignOut = false })
}

@Composable
private fun SettingsList(
	session: AppSession,
	themes: Themes,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	selected: SettingsPage?,
	onOpen: (SettingsPage) -> Unit,
	onSignOut: () -> Unit,
) {
	val state by session.state.collectAsStateWithLifecycle()
	val diagnostics by Diag.settings.config.collectAsStateWithLifecycle()
	val diagnosticsSummary = if (diagnostics?.let { it.configured && it.live } == true) "Sending live logs" else "Kept on this tablet"
	val themeSummary = remember(choice) { summary(themes, choice) }
	val playbackSummary = rememberPlaybackPrefs().value?.let { "Trailers ${onOff(it.playsTrailers)} · Theme music ${onOff(it.playsThemeMusic)}" } ?: ""
	val container = LocalContext.current.container
	val homeLabber by container.homeLabber.link.collectAsStateWithLifecycle()
	val seerr by container.seerr.config.collectAsStateWithLifecycle()
	val bazarr by container.subtitleServices.bazarr.collectAsStateWithLifecycle()
	val speechDock by container.subtitleServices.speechDock.collectAsStateWithLifecycle(null)
	val remote by container.tvRemote.state.collectAsStateWithLifecycle()
	val link by container.link.state.collectAsStateWithLifecycle()
	val linkSummary = if (!link.enabled) "Off" else if (link.phones.isEmpty()) "On" else "${link.phones.size} linked"
	val profile = state.user?.uuid.orEmpty()
	val hidden by remember(profile) { container.browse.preferences.hiddenLibraries(profile) }.collectAsStateWithLifecycle(emptySet())
	val seerrSummary = when {
		seerr.enabled -> "On"
		seerr.configured -> "Off"
		else -> "Not set up"
	}
	val posterSummary = LocalPosterLabels.current.summary
	BrowseScaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
		BrowseList(
			modifier = Modifier.fillMaxSize(),
			contentPadding = padding.plusInset(),
			verticalArrangement = Arrangement.spacedBy(GroupGap),
		) {
			item(key = "account") { Heading("Account") }
			item(key = "profile") {
				PageRow(0, 2, SettingsPage.Profile, selected, onOpen) {
					Avatar(state.user, size = 40.dp)
					Labels(state.user?.title ?: "Profile", "Switch profile")
				}
			}
			item(key = "server") {
				PageRow(1, 2, SettingsPage.Server, selected, onOpen) {
					IconBadge(R.drawable.ic_dns)
					Labels("Server", state.server?.name ?: "None chosen")
				}
			}
			item(key = "appearance") { Heading("Appearance") }
			item(key = "theme") {
				PageRow(0, 4, SettingsPage.Appearance, selected, onOpen) {
					IconBadge(R.drawable.ic_palette)
					Labels("Theme", themeSummary)
				}
			}
			item(key = "tabBar") {
				PageRow(1, 4, SettingsPage.TabBar, selected, onOpen) {
					IconBadge(R.drawable.ic_grid_view)
					Labels("Tab Bar", "Which places are tabs, and their order")
				}
			}
			item(key = "headerStyle") {
				PageRow(2, 4, SettingsPage.HeaderStyle, selected, onOpen) {
					IconBadge(R.drawable.ic_tune)
					Labels("Header Style", choice.headerStyle.name)
				}
			}
			item(key = "scrollBars") {
				AppearanceSwitch(3, 4, "Show Scroll Bars", "The Library’s A–Z rail still shows", choice.showsScrollBars) { on ->
					onTheme { it.copy(showsScrollBars = on) }
				}
			}
			item(key = "library") { Heading("Library") }
			item(key = "favoriteLibraries") {
				PageRow(0, 2, SettingsPage.FavoriteLibraries, selected, onOpen) {
					IconBadge(R.drawable.ic_video_library)
					Labels("Favorite Libraries", if (hidden.isEmpty()) "All" else "Some")
				}
			}
			item(key = "underPosters") {
				PageRow(1, 2, SettingsPage.UnderPosters, selected, onOpen) {
					IconBadge(R.drawable.ic_edit)
					Labels("Under Posters", posterSummary)
				}
			}
			item(key = "playback") { Heading("Playback") }
			item(key = "videoPlayback") {
				PageRow(0, 2, SettingsPage.VideoPlayback, selected, onOpen) {
					IconBadge(R.drawable.ic_movie)
					Labels("Video & Playback", playbackSummary)
				}
			}
			item(key = "downloads") {
				PageRow(1, 2, SettingsPage.Downloads, selected, onOpen) {
					IconBadge(R.drawable.ic_download)
					Labels("Downloads", "Quality, storage and offline playback")
				}
			}
			item(key = "integrations") { Heading("Integrations") }
			item(key = "seerr") {
				PageRow(0, 9, SettingsPage.Seerr, selected, onOpen) {
					IconBadge(R.drawable.ic_playlist_add)
					Labels("Seerr", seerrSummary)
				}
			}
			item(key = "bazarr") {
				PageRow(1, 9, SettingsPage.Bazarr, selected, onOpen) {
					IconBadge(R.drawable.ic_closed_caption)
					Labels("Bazarr", if (bazarr == null) "Not set up" else "Subtitle search")
				}
			}
			item(key = "speechDock") {
				PageRow(2, 9, SettingsPage.SpeechDock, selected, onOpen) {
					IconBadge(R.drawable.ic_match_case)
					Labels("SpeechDock", if (speechDock == null) "Off" else "Spoken subtitle timing")
				}
			}
			item(key = "homeLabber") {
				PageRow(3, 9, SettingsPage.HomeLabber, selected, onOpen) {
					IconBadge(R.drawable.ic_speed)
					Labels(SettingsPage.HomeLabber.title, if (homeLabber == null) "Off" else "On")
				}
			}
			item(key = "tvRemote") {
				PageRow(4, 9, SettingsPage.TvRemote, selected, onOpen) {
					IconBadge(R.drawable.ic_tv_remote)
					Labels(SettingsPage.TvRemote.title, if (remote.enabled) "On" else "Off")
				}
			}
			item(key = "bigScreen") {
				PageRow(5, 9, SettingsPage.BigScreen, selected, onOpen) {
					IconBadge(R.drawable.ic_glasses)
					Labels(SettingsPage.BigScreen.title, if (container.glasses.prefs.enabled) "On" else "Off")
				}
			}
			item(key = "chromecast") {
				PageRow(6, 9, SettingsPage.Chromecast, selected, onOpen) {
					IconBadge(R.drawable.ic_cast)
					Labels("Chromecast", if (container.cast[CastProvider.Chromecast].enabled) "On" else "Off")
				}
			}
			item(key = "airPlay") {
				PageRow(7, 9, SettingsPage.AirPlay, selected, onOpen) {
					IconBadge(R.drawable.ic_tv)
					Labels("AirPlay", if (container.cast[CastProvider.AirPlay].enabled) "On" else "Off")
				}
			}
			item(key = "linkedPhones") {
				PageRow(8, 9, SettingsPage.LinkedPhones, selected, onOpen) {
					IconBadge(R.drawable.ic_smartphone)
					Labels(SettingsPage.LinkedPhones.title, linkSummary)
				}
			}
			item(key = "about") { Heading("About") }
			item(key = "diagnostics") {
				PageRow(0, 2, SettingsPage.DiagnosticLogs, selected, onOpen) {
					IconBadge(R.drawable.ic_bug_report)
					Labels("Diagnostic Logs", diagnosticsSummary)
				}
			}
			item(key = "licences") {
				PageRow(1, 2, SettingsPage.Licences, selected, onOpen) {
					IconBadge(R.drawable.ic_description)
					Labels("Licences", "Manrope, FFmpeg")
				}
			}
			item(key = "signOut") {
				GroupRow(index = 0, count = 1, modifier = Modifier.padding(top = Space.s6), onClick = onSignOut) {
					Icon(painterResource(R.drawable.ic_logout), contentDescription = null, tint = lucent.danger)
					Text("Sign Out", style = MaterialTheme.typography.titleMedium, color = lucent.danger)
				}
			}
		}
	}
}

@Composable
private fun PageRow(
	index: Int,
	count: Int,
	page: SettingsPage,
	selected: SettingsPage?,
	onOpen: (SettingsPage) -> Unit,
	content: @Composable RowScope.() -> Unit,
) {
	GroupRow(index = index, count = count, selected = selected == page, onClick = { onOpen(page) }) {
		content()
		Chevron(R.drawable.ic_chevron_right)
	}
}

@Composable
private fun RowScope.Labels(title: String, detail: String) {
	Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
		Text(title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		Text(detail, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
	}
}

@Composable
private fun Chevron(@DrawableRes icon: Int) {
	Icon(painterResource(icon), contentDescription = null, tint = lucent.ink3)
}

@Composable
internal fun Heading(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelLarge,
		color = lucent.ink2,
		modifier = Modifier.padding(start = Space.s4, top = Space.s4, bottom = Space.s2),
	)
}

@Composable
private fun SettingsDetail(
	page: SettingsPage,
	session: AppSession,
	themes: Themes,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	onDownloads: () -> Unit,
	onBack: (() -> Unit)?,
) {
	BrowseScaffold(
		topBar = {
			TopAppBar(
				title = { Text(page.title) },
				navigationIcon = {
					if (onBack != null) {
						IconButton(onClick = onBack) {
							Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
						}
					}
				},
			)
		},
	) { padding ->
		when (page) {
			SettingsPage.Profile -> ProfilesPage(session, padding.plusInset())
			SettingsPage.Server -> ServerList(session, isRoot = false, onChosen = { onBack?.invoke() }, contentPadding = padding)
			SettingsPage.Appearance -> AppearancePage(themes, choice, onTheme, padding.plusInset())
			SettingsPage.TabBar -> TabBarPage(padding.plusInset())
			SettingsPage.HeaderStyle -> HeaderStylePage(choice.headerStyle, padding.plusInset()) { style ->
				onTheme { it.copy(headerStyle = style) }
			}
			SettingsPage.FavoriteLibraries -> FavoriteLibrariesPage(session, padding.plusInset())
			SettingsPage.UnderPosters -> {
				val profile by session.state.collectAsStateWithLifecycle()
				UnderPostersPage(LocalContext.current.container, profile.user?.uuid.orEmpty(), padding.plusInset())
			}
			SettingsPage.DiagnosticLogs -> DiagnosticLogsPage(padding.plusInset())
			SettingsPage.VideoPlayback -> VideoPlaybackPage(padding.plusInset())
			SettingsPage.Downloads -> DownloadSettingsPage(LocalContext.current.container.downloads, padding.plusInset(), onDownloads)
			SettingsPage.Seerr -> SeerrPage(padding.plusInset())
			SettingsPage.Bazarr -> SubtitleServicePage(SubtitleService.Bazarr, padding.plusInset())
			SettingsPage.SpeechDock -> SubtitleServicePage(SubtitleService.SpeechDock, padding.plusInset())
			SettingsPage.HomeLabber -> HomeLabberPage(padding.plusInset())
			SettingsPage.TvRemote -> TvRemoteSettings(padding.plusInset())
			SettingsPage.BigScreen -> BigScreenPage(padding.plusInset())
			SettingsPage.Chromecast -> CastSettingsPage(padding.plusInset(), CastProvider.Chromecast)
			SettingsPage.AirPlay -> CastSettingsPage(padding.plusInset(), CastProvider.AirPlay)
			SettingsPage.LinkedPhones -> LinkSettings(padding.plusInset())
			SettingsPage.Licences -> LicencesPage(padding.plusInset())
		}
	}
}

/** The Theme row's detail: both themes while following the system, otherwise the one in use. */
private fun summary(themes: Themes, choice: ThemeChoice): String {
	fun name(mode: ThemeMode) = themes.resolve(choice.id(mode), mode).lucent.name
	return if (choice.matchSystem) "${name(ThemeMode.Light)} · ${name(ThemeMode.Dark)}" else name(choice.fixedMode)
}

private fun onOff(on: Boolean) = if (on) "on" else "off"

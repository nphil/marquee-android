package com.nphil.marquee.television

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.SubtitleService
import com.nphil.marquee.ui.diagnostics.DiagnosticLogsPage
import com.nphil.marquee.ui.profiles.ProfilesPage
import com.nphil.marquee.ui.servers.ServerList
import com.nphil.marquee.ui.settings.*
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val group: String, val summary: String) {
    Appearance("Appearance", "Personalize", "Choose a theme for this screen."),
    Playback("Video & Playback", "Personalize", "Trailers, streaming quality, picture enhancement and autoplay."),
    Subtitles("Subtitle Style", "Personalize", "Change how subtitles look over the picture."),
    Libraries("Libraries", "Personalize", "Choose which libraries appear in Marquee."),
    HomeRows("Home Rows", "Personalize", "Choose which shelves appear on Home and their order."),
    Posters("Under Posters", "Personalize", "Choose the title, details and sort value shown under posters."),
    TopBar("Top Bar", "Personalize", "Choose which shortcuts appear above the Home shelves."),
    Profiles("Profiles", "Plex Home", "Switch who is watching, with their own settings and Watchlist."),
    Servers("Servers", "Plex Home", "Choose a server owned by or shared with this account."),
    Seerr("Seerr", "Integrations", "Connect Seerr to browse and request titles not in the library."),
    Bazarr("Bazarr", "Integrations", "Find subtitles for films and episodes."),
    Speech("SpeechDock", "Integrations", "Align English subtitles with the audio for Reveal as spoken."),
    Logs("Diagnostics", "System", "Review the app’s diagnostic log and reporting options."),
    Tour("Remote tour", "System", "Revisit the six remote controls lessons."),
    Licences("Licences", "System", "Third-party software and its licences."),
    SignOut("Sign Out", "System", "Unlink this TV from the current Plex account.")
}

@Composable internal fun TvSettings(container: AppContainer, close: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<Destination?>(null) }
    var last by rememberSaveable { mutableStateOf(Destination.Appearance) }
    val state by container.session.state.collectAsStateWithLifecycle()
    val restricted = state.user?.let { it.isRestricted || it.isGuest } != false
    val choices = remember(restricted) { Destination.entries.filterNot { restricted && (it.group == "Integrations" || it == Destination.Logs) } }
    val padding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
    // A restored page must obey the same profile permissions as its menu entry.
    val current = page?.takeIf { it in choices }
    if (page != null && current == null) LaunchedEffect(page) { page = null }
    BackHandler(current != null) { page = null }
    if (current == null) {
        val list = rememberLazyListState(choices.indexOf(last).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        val restore = remember { last.takeIf { it in choices } ?: choices.first() }
        var initialFocus by remember { mutableStateOf(true) }
        Row(Modifier.fillMaxSize().background(lucent.canvas).padding(34.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(.8f).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Settings", color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
                Text(last.takeIf { it in choices }?.summary ?: restore.summary, color = lucent.ink2, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(Modifier.weight(1.2f), state = list, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(choices, key = { _, d -> d.name }) { i, d ->
                    if (i == 0 || choices[i-1].group != d.group) Text(d.group, Modifier.padding(top = 9.dp), color = lucent.accentText, style = MaterialTheme.typography.labelLarge)
                    var selected by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(10.dp)
                    Text(d.label, Modifier.fillMaxWidth().then(if (d == restore) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { selected = it.isFocused; if (it.isFocused) last = d }.clip(shape)
                        .background(if (selected) lucent.glassRaised else lucent.sheet).then(if (selected) Modifier.border(2.dp, lucent.accent, shape) else Modifier)
                        .clickable { last = d; page = d }.padding(horizontal = 16.dp, vertical = 9.dp), color = lucent.ink)
                    if (d == restore && initialFocus) LaunchedEffect(Unit) { focus.requestFocus(); initialFocus = false }
                }
            }
        }
        return
    }
    if (current == Destination.Subtitles) { TvSubtitleStyle(container); return }
    if (current == Destination.Tour) { TvTour { page = null }; return }
    Column(Modifier.fillMaxSize().background(lucent.canvas).padding(horizontal = 28.dp, vertical = 16.dp)) {
        Text("Settings  /  ${current.group}", color = lucent.ink2, style = MaterialTheme.typography.labelLarge)
        Text(current.label, Modifier.padding(bottom = 8.dp), color = lucent.ink, style = MaterialTheme.typography.headlineMedium)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (current) {
                Destination.Appearance -> {
                    val choice by container.settings.themeChoice.collectAsStateWithLifecycle(ThemeChoice())
                    AppearancePage(container.themes, choice, { change -> container.scope.launch { container.settings.updateTheme(change) } }, padding)
                }
                Destination.Playback -> VideoPlaybackPage(padding)
                Destination.Subtitles, Destination.Tour -> Unit // These have their own full-screen layouts.
                Destination.Libraries -> FavoriteLibrariesPage(container.session, padding)
                Destination.HomeRows -> TvHomeRows(container)
                Destination.Posters -> UnderPostersPage(container, state.user?.uuid.orEmpty(), padding)
                Destination.TopBar -> TvTopBar(container, state.user?.uuid.orEmpty())
                Destination.Profiles -> ProfilesPage(container.session, padding)
                Destination.Servers -> ServerList(container.session, false, close, padding)
                Destination.Seerr -> SeerrPage(padding)
                Destination.Bazarr -> SubtitleServicePage(SubtitleService.Bazarr, padding)
                Destination.Speech -> SubtitleServicePage(SubtitleService.SpeechDock, padding)
                Destination.Logs -> DiagnosticLogsPage(padding)
                Destination.Licences -> LicencesPage(padding)
                Destination.SignOut -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Unlink this TV from Plex?", color = lucent.ink)
                    Action("Keep linked") { page = null }
                    Action("Unlink account") { container.session.signOut() }
                }
            }
        }
    }
}

@Composable private fun TvTopBar(container: AppContainer, profile: String) {
    val hidden by remember(profile) { container.settings.hiddenTvShortcuts(profile) }.collectAsStateWithLifecycle(emptySet())
    val requests by container.seerr.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Home and Settings are always available.", color = lucent.ink2)
        for (shortcut in com.nphil.marquee.data.TvShortcut.entries) {
            if (shortcut == com.nphil.marquee.data.TvShortcut.Requests && !requests) continue
            val shown = shortcut.name !in hidden
            val focus = if (shortcut == com.nphil.marquee.data.TvShortcut.Movies) Modifier.focusRequester(first) else Modifier
            Row(Modifier.fillMaxWidth().then(focus), verticalAlignment = Alignment.CenterVertically) {
                Text(shortcut.label, Modifier.weight(1f), color = lucent.ink)
                Action(if (shown) "Hide ${shortcut.label}" else "Show ${shortcut.label}") {
                    scope.launch { container.settings.setTvShortcut(profile, shortcut, !shown) }
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

private val lessons = listOf(
    "Browse the shelves" to "Move across a shelf to explore titles or down to the next shelf. Select a poster to open it.",
    "Return to the picture" to "Back closes the current panel first. In the player it hides the controls before leaving the video.",
    "Open a title’s menu" to "Hold the center of the remote on a poster to see actions for that title.",
    "Playback controls" to "Open the timeline while watching. Audio and subtitles sit above it, with information and chapters below.",
    "Preview a seek" to "Move left or right on the timeline to preview a moment. Select to jump there, or return without moving playback.",
    "Switch profiles" to "Open Profiles in Settings to change who’s watching. Each profile keeps its own preferences and Watchlist.",
)

@Composable internal fun TvTour(close: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    fun next() { if (step == lessons.lastIndex) close() else step++ }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(lucent.canvas).onPreviewKeyEvent {
        if (it.key != Key.DirectionLeft && it.key != Key.DirectionRight) false
        else {
            if (it.type == KeyEventType.KeyUp) {
                if (it.key == Key.DirectionLeft) step = (step - 1).coerceAtLeast(0) else next()
            }
            true
        }
    }.padding(42.dp), verticalArrangement = Arrangement.Center) {
        Text("Remote tour  ·  ${step + 1} of ${lessons.size}", color = lucent.accentText, style = MaterialTheme.typography.labelLarge)
        Text(lessons[step].first, Modifier.padding(vertical = 10.dp), color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
        Text(lessons[step].second, Modifier.widthIn(max = 520.dp), color = lucent.ink2, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.focusRequester(focus)) { Action(if (step == lessons.lastIndex) "Done" else "Next", ::next) }
        Action("Close tour", close)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

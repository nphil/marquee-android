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
    Profiles("Profiles", "Plex Home", "Switch who is watching, with their own settings and Watchlist."),
    Servers("Servers", "Plex Home", "Choose a server owned by or shared with this account."),
    Seerr("Seerr", "Integrations", "Connect Seerr to browse and request titles not in the library."),
    Bazarr("Bazarr", "Integrations", "Find subtitles for films and episodes."),
    Speech("SpeechDock", "Integrations", "Connect the speech service for subtitle transcription."),
    Logs("Diagnostics", "System", "Review the app’s diagnostic log and reporting options."),
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
    val current = page
    BackHandler(current != null) { page = null }
    if (current == null) {
        val list = rememberLazyListState(choices.indexOf(last).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        val restore = remember { last.takeIf { it in choices } ?: choices.first() }
        var initialFocus by remember { mutableStateOf(true) }
        Row(Modifier.fillMaxSize().background(lucent.canvas).padding(34.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(.8f).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Settings", color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
                Text(last.summary, color = lucent.ink2, style = MaterialTheme.typography.titleMedium)
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
                Destination.Subtitles -> Unit // Has its own preview layout.
                Destination.Libraries -> FavoriteLibrariesPage(container.session, padding)
                Destination.HomeRows -> TvHomeRows(container)
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

package com.nphil.marquee.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShowRecordingAirings(container: AppContainer, title: String, close: () -> Unit) {
    val model by container.live.guide.collectAsStateWithLifecycle()
    val guide = model
    var selected by remember(guide, title) { mutableStateOf<Pair<GuideChannel, GuideProgramme>?>(null) }
    var loaded by remember(guide) { mutableStateOf(false) }
    LaunchedEffect(guide) { guide?.refresh(wait = true); guide?.dvr?.refreshSchedule(); loaded = true }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(Space.inset)) {
            Text("Choose an airing", style = MaterialTheme.typography.titleLarge)
            val window = guide?.guide
            val picks = remember(window, title) { window?.channels.orEmpty().flatMap { channel ->
                window?.airings?.get(channel.id).orEmpty().filter { it.end > (guide?.now ?: 0.0) && it.title.equals(title, ignoreCase = true) }.map { channel to it }
            }.sortedBy { it.second.start } }
            val pick = selected
            when {
                !loaded -> CircularProgressIndicator()
                guide == null || guide.noDvr -> Text("Live TV isn’t set up on this server.")
                window == null && guide.problem != null -> Text(guide.problem.orEmpty())
                picks.isEmpty() -> Text("No showing of $title in the loaded guide.")
                pick != null -> {
                    Text(pick.second.episodeTitle ?: pick.second.title)
                    Text("${rememberGuideTime(pick.second.start, true)} · ${pick.first.label}")
                    DvrProgrammeActions(guide.dvr, pick.first.id, pick.second, guide.now)
                    TextButton({ selected = null }) { Text("Other airings") }
                }
                else -> LazyColumn { items(picks) { (channel, programme) ->
                    TextButton({ selected = channel to programme }) { Text("${programme.episodeTitle ?: programme.title} · ${rememberGuideTime(programme.start, true)} · ${channel.label}") }
                } }
            }
        }
    }
}

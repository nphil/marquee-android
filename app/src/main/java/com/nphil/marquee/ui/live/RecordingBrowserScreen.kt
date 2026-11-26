package com.nphil.marquee.ui.live

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nphil.marquee.core.*
import com.nphil.marquee.live.*
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun RecordingBrowserScreen(model: DvrModel, modifier: Modifier, play: (DvrItem) -> Unit) {
    val browser = model.recordings
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf("Upcoming") }
    var selected by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf<DvrAiring?>(null) }
    var remove by remember { mutableStateOf<DvrRule?>(null) }
    var page by remember(browser) { mutableStateOf<RecordingPage?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var alerts by remember(browser) { mutableStateOf(browser.alerts.enabled(browser.key)) }
    val act: (suspend () -> Unit) -> Unit = { change -> if (!busy) { busy = true; scope.launch {
        try { change(); error = null; refresh++ } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.localizedMessage } finally { busy = false }
    } } }
    fun enable() = act { browser.observe(true); browser.alerts.enable(browser.key, true); alerts = true }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) enable() else error = "Recording notifications are off. Allow them in Android Settings." }
    LaunchedEffect(browser, refresh) {
        while (true) {
            try { page = browser.page(); model.refreshSchedule(); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.localizedMessage }
            delay(30_000)
        }
    }
    LaunchedEffect(tab, refresh) { if (tab == "Recorded") model.loadRecorded(true) }
    var options by remember { mutableStateOf(false) }
    Column(modifier.background(lucent.canvas).testTag("live-recordings").padding(horizontal = Space.inset)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s1)) { listOf("Upcoming", "Rules", "Recorded", "History").forEach {
            FilterChip(tab == it, { tab = it }, { Text(it) })
        } }
        Box {
            TextButton({ options = true }) { Text("Recording options") }
            DropdownMenu(expanded = options, onDismissRequest = { options = false }, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(text = { Text(if (alerts) "Turn alerts off" else "Turn alerts on") }, enabled = !busy, onClick = {
                    options = false
                    if (alerts) { browser.alerts.enable(browser.key, false); alerts = false }
                    else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enable()
                })
                DropdownMenuItem(text = { Text("Refresh schedule") }, onClick = { options = false; refresh++ })
                page?.storage?.let { Text(it, Modifier.widthIn(max = 280.dp).padding(Space.inset), color = lucent.ink2, style = MaterialTheme.typography.bodySmall) }
            }
        }
        (error ?: if (tab == "Recorded") model.recordedError else null)?.let { DvrNotice(it) }
        val data = page
        if (data == null) { if (error == null) CircularProgressIndicator() } else BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= data.splitMinWidth.dp
            BackHandler(!wide && selected != null && edit == null && cancel == null && remove == null) { selected = null }
            Row(Modifier.fillMaxSize()) {
                if (wide || selected == null) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    when (tab) {
                        "Upcoming" -> {
                            if (data.days.isEmpty()) item { Text("Nothing scheduled") }
                            data.days.forEach { day -> item { Text(day.heading, Modifier.padding(vertical = Space.s2), color = lucent.ink, style = MaterialTheme.typography.titleMedium) }; items(day.items, key = { it.id }) { grab ->
                                val target = model.schedule?.scheduled?.find { it.id == grab.id }
                                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) cancel = target
                                    false
                                })
                                SwipeToDismissBox(dismiss, backgroundContent = { Text(if (grab.isRecording) "Stop" else "Skip") }, enableDismissFromStartToEnd = false) {
                                    val time = "${rememberGuideTime(grab.start)} – ${rememberGuideTime(grab.end)}"
                                    BrowserRow(grab.title, listOfNotNull(time, grab.channel).joinToString(" · "), { selected = grab.id }, { edit = grab.ruleID }, conflict = grab.conflictID != null,
                                        metadata = listOfNotNull(grab.episodeTitle, grab.freshness, grab.status).joinToString(" · "))
                                }
                            } }
                        }
                        "Rules" -> {
                            if (data.rules.isEmpty()) item { Text("No recording rules") }
                            itemsIndexed(data.rules, key = { _, rule -> rule.id }) { index, rule ->
                                var height by remember { mutableIntStateOf(1) }
                                Row(Modifier.onSizeChanged { height = it.height }) {
                                    Box(Modifier.weight(1f)) { BrowserRow(rule.title, rule.summary, { selected = rule.id }, { edit = rule.id }) }
                                    Text("☰", Modifier.pointerInput(data.rules, height) {
                                        var dy = 0f
                                        detectDragGesturesAfterLongPress(onDragStart = { dy = 0f }, onDragEnd = {
                                            val to = (index + (dy / height.coerceAtLeast(1)).roundToInt()).coerceIn(data.rules.indices)
                                            if (to != index) act { browser.move(rule.id, index, to) }
                                        }) { change, delta -> change.consume(); dy += delta.y }
                                    }.padding(Space.s3))
                                }
                            }
                        }
                        "Recorded" -> { if (model.recorded?.isEmpty() == true) item { Text("No identifiable recordings") }; items(model.recorded.orEmpty(), key = { it.ratingKey }) { recording -> BrowserRow(recording.title, recording.episodeTitle.orEmpty(), { selected = recording.ratingKey }, tag = "recorded-${recording.ratingKey}") } }
                        "History" -> { if (data.history.isEmpty()) item { Text("No recording history yet") }; items(data.history, key = { it.id }) { entry -> BrowserRow(entry.title, entry.outcome, { selected = entry.id }) } }
                    }
                }
                if (wide || selected != null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.inset), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (!wide) TextButton({ selected = null }) { Text("Back to $tab") }
                    if (selected == null) Text("Choose a recording or rule")
                    when (tab) {
                        "Upcoming" -> data.days.asSequence().flatMap { it.items.asSequence() }.find { it.id == selected }?.let { grab ->
                            Text(grab.title, style = MaterialTheme.typography.headlineSmall); Text(listOfNotNull(grab.episodeTitle, grab.channel, rememberGuideTime(grab.start, true), grab.status, grab.padding).joinToString(" · "))
                            data.conflicts.find { it.id == grab.conflictID }?.let { conflict ->
                                Text(conflict.sentence, color = lucent.danger)
                                conflict.losers.find { it.id == grab.id }?.let { loser ->
                                    if (loser.alternatives.isEmpty()) Text("No other free airing in the loaded guide.")
                                    loser.alternatives.forEach { other -> TextButton({ act { browser.switch(grab.id, other.id); selected = null } }, enabled = !busy) { Text("Switch to ${rememberGuideTime(other.start, true)} ${other.channel.orEmpty()}") } }
                                }
                            }
                            grab.ruleID?.let { TextButton({ edit = it }) { Text("Edit rule") } }
                            TextButton({ cancel = model.schedule?.scheduled?.find { it.id == grab.id } }) { Text(if (grab.isRecording) "Stop recording" else "Skip airing") }
                        }
                        "Rules" -> data.rules.find { it.id == selected }?.let { rule -> Text(rule.title, style = MaterialTheme.typography.headlineSmall); Text(rule.summary); TextButton({ edit = rule.id }) { Text("Edit rule") }; TextButton({ remove = model.schedule?.rules?.find { it.id == rule.id } }) { Text("Delete rule") } }
                        "History" -> data.history.find { it.id == selected }?.let { Text(it.title); Text(rememberGuideTime(it.airedAt, true)); Text(it.reason) }
                        "Recorded" -> model.recorded?.find { it.ratingKey == selected }?.let { Text(it.title); Button({ play(it) }) { Text("Play recording") } }
                    }
                }
            }
        }
    }
    edit?.let { RecordEditor(model, null, null, it) { edit = null; refresh++ } }
    cancel?.let { grab -> DvrCancelDialog(model, grab, { cancel = null }) { series -> cancel = null; model.unschedule(grab, series) { error = it; if (it == null) { selected = null; refresh++ } } } }
    remove?.let { rule -> DvrConfirm("Delete rule", model.working, { remove = null }, { remove = null; model.delete(rule) { error = it; if (it == null) { selected = null; refresh++ } } }) { Text("Existing recordings stay in the library.") } }
}

@Composable private fun BrowserRow(title: String, subtitle: String, select: () -> Unit, hold: (() -> Unit)? = null, conflict: Boolean = false, tag: String = "", metadata: String = "") {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().testTag(tag).combinedClickable(onClick = select, onLongClick = hold).padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(title, color = lucent.ink, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) Text(subtitle, color = lucent.ink, style = MaterialTheme.typography.bodyMedium)
        if (metadata.isNotBlank()) Text(metadata, color = lucent.ink2, style = MaterialTheme.typography.bodySmall)
        if (conflict) Text("Conflict", color = lucent.danger)
    } }
}

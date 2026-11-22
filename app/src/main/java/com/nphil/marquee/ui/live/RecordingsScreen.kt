package com.nphil.marquee.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.DvrAiring
import com.nphil.marquee.core.DvrItem
import com.nphil.marquee.core.DvrMark
import com.nphil.marquee.core.DvrRule
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.live.DvrModel
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.Recordings
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsDestination(container: AppContainer, route: Recordings, back: () -> Unit) {
	val guide by container.live.guide.collectAsStateWithLifecycle()
	val model = guide?.takeIf { it.key == route.scope }?.dvr
	val navigator = LocalNavigator.current
	Scaffold(containerColor = lucent.canvas, topBar = {
		TopAppBar(title = { Text("Recordings", Modifier.semantics { heading() }) }, navigationIcon = { BackArrow(back) })
	}) { padding ->
		if (model == null) {
			EmptyState(R.drawable.ic_tv, "Recordings unavailable", "Open Live TV on the active profile.", Modifier.fillMaxSize().padding(padding))
		} else {
			RecordingsScreen(model, Modifier.fillMaxSize().padding(padding)) { item ->
				navigator?.openRecording(item.ratingKey, item.titleKey, item.mediaID)
			}
		}
	}
}

private enum class RecordingTab(val label: String) { Scheduled("Scheduled"), Rules("Series rules"), Recorded("Recorded") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordingsScreen(model: DvrModel, modifier: Modifier, play: (DvrItem) -> Unit) {
	var tabName by rememberSaveable { mutableStateOf(RecordingTab.Scheduled.name) }
	val tab = RecordingTab.valueOf(tabName)
	val scope = rememberCoroutineScope()
	var loading by remember(model) { mutableStateOf(false) }
	var actionError by remember(model) { mutableStateOf<String?>(null) }
	var airingToCancel by remember(model) { mutableStateOf<DvrAiring?>(null) }
	var ruleToEdit by remember(model) { mutableStateOf<DvrRule?>(null) }
	var ruleToDelete by remember(model) { mutableStateOf<DvrRule?>(null) }
	val load: suspend (Boolean) -> Unit = { refresh ->
		if (tab == RecordingTab.Recorded) model.loadRecorded(refresh)
		else if (!loading) {
			loading = true
			try { model.refreshSchedule() } finally { loading = false }
		}
	}
	LaunchedEffect(model, tab) { load(false) }
	val refresh = { scope.launch { load(true) }; Unit }
	val error = if (tab == RecordingTab.Recorded) model.recordedError else model.scheduleError
	val schedule = model.schedule
	val scheduled = remember(schedule) { schedule?.scheduled.orEmpty().filter { it.isUpcoming || it.mark == DvrMark.Failed } }
	val rules = remember(schedule) { schedule?.rules.orEmpty().filter { it.isSeries }.sortedBy { it.title.lowercase() } }
	val recorded = model.recorded.orEmpty()
	val dataLoaded = if (tab == RecordingTab.Recorded) model.recorded != null else schedule != null
	val refreshing = if (tab == RecordingTab.Recorded) model.recordedLoading else loading
	Column(modifier.testTag("live-recordings")) {
		FlowRow(Modifier.fillMaxWidth().padding(horizontal = Space.inset), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			for (page in RecordingTab.entries) {
				FilterChip(tab == page, { tabName = page.name; actionError = null }, { Text(page.label) }, modifier = Modifier.heightIn(min = Size.target))
			}
		}
		Column(Modifier.fillMaxWidth().padding(horizontal = Space.inset)) {
			if (ruleToEdit == null) actionError?.let { DvrNotice(it) }
			if (error != null && dataLoaded) {
				DvrNotice(error)
				TextAction("Try again", refresh)
			}
		}
		if (model.working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = lucent.accent)
		PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
			val empty = when {
				!dataLoaded && error != null -> "Recordings unavailable" to error
				!dataLoaded -> "Loading ${tab.label.lowercase()}…" to "Reading Plex."
				tab == RecordingTab.Scheduled && scheduled.isEmpty() -> "Nothing scheduled" to "Choose a programme in the guide to record it."
				tab == RecordingTab.Rules && rules.isEmpty() -> "No series rules" to "Record a series from the guide."
				tab == RecordingTab.Recorded && recorded.isEmpty() -> "No identifiable recordings" to "Only recordings Plex still identifies as DVR appear here. Others may be in the library."
				else -> null
			}
			if (empty != null) {
				EmptyState(R.drawable.ic_video_library, empty.first, empty.second, Modifier.align(Alignment.Center)) {
					if (error != null || dataLoaded) RetryButton(if (error == null) "Refresh" else "Try again", refresh)
				}
			} else {
				LazyColumn(Modifier.fillMaxSize()) {
					when (tab) {
						RecordingTab.Scheduled -> items(scheduled, key = { "airing:${it.id}" }) { airing ->
							ScheduledRow(airing, model.canRecord && !model.working) { actionError = null; airingToCancel = airing }
							HorizontalDivider(color = lucent.edge)
						}
						RecordingTab.Rules -> items(rules, key = { "rule:${it.id}" }) { rule ->
							RuleRow(rule, model.canRecord && !model.working,
								edit = { actionError = null; ruleToEdit = rule }, delete = { actionError = null; ruleToDelete = rule })
							HorizontalDivider(color = lucent.edge)
						}
						RecordingTab.Recorded -> items(recorded, key = { "recorded:${it.ratingKey}" }) { item ->
							RecordedRow(item) { play(item) }
							HorizontalDivider(color = lucent.edge)
						}
					}
				}
			}
		}
	}
	airingToCancel?.let { airing ->
		DvrCancelDialog(model, airing, { airingToCancel = null }) { deletingSeries ->
			airingToCancel = null
			model.unschedule(airing, deletingSeries) { actionError = it }
		}
	}
	ruleToEdit?.let { rule ->
		DvrSettingsDialog(rule.title, rule.library?.let { "Saves to $it." }, rule.settings, "Save changes", model.working,
			error = actionError, dismiss = { ruleToEdit = null }, confirm = { values ->
				actionError = null
				model.update(rule, values) { error ->
					actionError = error
					if (error == null) ruleToEdit = null
				}
			})
	}
	ruleToDelete?.let { rule ->
		DvrConfirm("Delete series rule", model.working, { ruleToDelete = null }, confirm = {
			ruleToDelete = null
			model.delete(rule) { actionError = it }
		}) {
			Text("Stops current and cancels future recordings of ${rule.title}. Finished recordings stay in the library.")
		}
	}
}

@Composable
private fun ScheduledRow(airing: DvrAiring, editable: Boolean, cancel: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s3)) {
		Text(airing.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		DvrRowDetail(airing.episodeCode, airing.episodeTitle)
		DvrRowDetail(airing.channelNumber, rememberGuideTime(airing.start, includeDay = true))
		DvrNotice(airing.mark.label, isError = airing.mark == DvrMark.Failed)
		if (airing.isUpcoming) RowAction(if (airing.isRecording) "Stop recording" else "Cancel airing", editable, cancel)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(rule: DvrRule, editable: Boolean, edit: () -> Unit, delete: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(horizontal = Space.inset, vertical = Space.s3)) {
		Text(rule.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		DvrRowDetail(rule.airingsType, rule.library)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			if (rule.settings.isNotEmpty()) RowAction("Edit rule", editable, edit)
			RowAction("Delete rule", editable, delete)
		}
	}
}

@Composable
private fun RecordedRow(item: DvrItem, play: () -> Unit) {
	Row(Modifier.fillMaxWidth().heightIn(min = Size.target).testTag("recorded-${item.ratingKey}")
		.clickable(role = Role.Button, onClickLabel = "Play recording", onClick = play)
		.padding(horizontal = Space.inset, vertical = Space.s3),
		horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
	) {
		PlexArt(item.thumb, 96.dp, 54.dp, Modifier.clip(RoundedCornerShape(Radius.control)))
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(item.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			DvrRowDetail(item.episodeCode, item.episodeTitle, item.year?.toString())
			DvrRowDetail(ItemText.runtime(item.duration), item.recordedAt?.let { "Recorded ${rememberGuideDate(it)}" })
			if (item.duration > 0 && item.viewOffset > 0) {
				LinearProgressIndicator({ (item.viewOffset.toFloat() / item.duration).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = lucent.accent)
			}
		}
	}
}

@Composable
private fun DvrRowDetail(vararg detail: String?) {
	val text = detail.filterNotNull().filter(String::isNotBlank).joinToString(" · ")
	if (text.isNotEmpty()) DvrNotice(text, isError = false)
}

@Composable
private fun RowAction(label: String, enabled: Boolean, action: () -> Unit) {
	TextButton(action, Modifier.heightIn(min = Size.target), enabled = enabled) { Text(label) }
}

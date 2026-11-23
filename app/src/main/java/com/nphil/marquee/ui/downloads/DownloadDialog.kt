package com.nphil.marquee.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.downloads.DownloadChoice
import com.nphil.marquee.downloads.DownloadOffer
import com.nphil.marquee.downloads.DownloadPhase
import com.nphil.marquee.downloads.Downloads
import com.nphil.marquee.downloads.downloadMessage
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException

internal fun Modifier.downloadDialogWidth(): Modifier =
	padding(horizontal = Space.inset).widthIn(max = 560.dp).fillMaxWidth()

/** Detail-page entry points; a saved copy always opens its private file, never a stream with the same rating key. */
internal class DownloadAction(val label: String, val icon: Int, val selected: Boolean = false, val open: () -> Unit)

@Composable
internal fun downloadAction(item: PlexItem?, version: Int, downloads: Downloads, navigator: Navigator, onServer: Boolean): DownloadAction? {
	val records by downloads.records.collectAsStateWithLifecycle()
	val title = item ?: return null
	val saved = records.firstOrNull { it.title.ratingKey == title.ratingKey }
	if (saved != null) return when {
		saved.phase == DownloadPhase.Ready -> DownloadAction("Play Download", R.drawable.ic_play_arrow, selected = true) { navigator.openDownload(saved) }
		saved.phase.active -> DownloadAction("Pause Download", R.drawable.ic_pause) { downloads.pause(saved) }
		saved.phase == DownloadPhase.Paused && !saved.submissionPending -> DownloadAction("Resume Download", R.drawable.ic_download) { downloads.resume(saved) }
		saved.phase == DownloadPhase.Failed && !saved.submissionPending -> DownloadAction("Retry Download", R.drawable.ic_refresh) { downloads.resume(saved) }
		else -> null
	}
	if (!onServer) return null
	val label = when (title.type) {
		MediaType.Movie, MediaType.Episode -> "Download…"
		MediaType.Show -> "Download Show…"
		MediaType.Season -> "Download Season…"
		else -> return null
	}
	var open by rememberSaveable(title.ratingKey) { mutableStateOf(false) }
	if (open) DownloadDialog(title, version, downloads, onDismiss = { open = false })
	return DownloadAction(label, R.drawable.ic_download) { open = true }
}

@Composable
private fun DownloadDialog(item: PlexItem, version: Int, downloads: Downloads, onDismiss: () -> Unit) {
	if (item.type == MediaType.Show || item.type == MediaType.Season) {
		val prefs by downloads.preferences.collectAsStateWithLifecycle()
		AlertDialog(
			onDismissRequest = onDismiss, title = { Text("Download episodes?") },
			modifier = Modifier.downloadDialogWidth(), properties = DialogProperties(usePlatformDefaultWidth = false),
			text = {
				Text(
					"Adds the episodes of ${item.title} at ${prefs.quality.label.lowercase()} quality. Saved episodes aren't added twice.",
					modifier = Modifier.verticalScroll(rememberScrollState()),
				)
			},
			confirmButton = { TextButton(onClick = { downloads.enqueueEpisodes(item); onDismiss() }) { Text("Download episodes") } },
			dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
		)
		return
	}
	var offer by remember(item.ratingKey, version) { mutableStateOf<DownloadOffer?>(null) }
	var failure by remember(item.ratingKey, version) { mutableStateOf<String?>(null) }
	var retry by remember { mutableIntStateOf(0) }
	var chosen by rememberSaveable(item.ratingKey, version) { mutableStateOf<String?>(null) }
	LaunchedEffect(downloads, item.ratingKey, version, retry) {
		failure = null
		try {
			val loaded = downloads.offer(item, version)
			offer = loaded
			if (chosen == null) chosen = loaded.choices.preferred
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			failure = downloadMessage(e)
		}
	}
	val ready = offer
	val choice = ready?.choices?.options?.find { it.id == chosen && it.refusal == null }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Download quality") },
		modifier = Modifier.downloadDialogWidth(), properties = DialogProperties(usePlatformDefaultWidth = false),
		text = {
			Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				val error = failure
				when {
					error != null -> {
						ErrorLine(error)
						RetryButton("Try again") { retry++ }
					}
					ready == null -> LoadingIndicator(color = lucent.accent)
					else -> {
						Text("${item.title} · Sizes are estimates", style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
						DownloadQualityOptions(ready.choices.options, chosen) { chosen = it }
					}
				}
			}
		},
		confirmButton = {
			TextButton(enabled = choice != null, onClick = {
				if (ready != null && choice != null) {
					downloads.enqueue(ready, choice)
					onDismiss()
				}
			}) { Text("Download") }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadQualityOptions(options: List<DownloadChoice>, chosen: String?, onChoose: (String) -> Unit) {
	BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
		val sideBySide = maxWidth >= 420.dp && LocalDensity.current.fontScale < 1.5f
		val columns = if (sideBySide) 2 else 1
		val gap = if (sideBySide) Space.s2 else GroupGap
		FlowRow(
			modifier = Modifier.fillMaxWidth(),
			maxItemsInEachRow = columns,
			horizontalArrangement = Arrangement.spacedBy(gap),
			verticalArrangement = Arrangement.spacedBy(gap),
		) {
			options.forEachIndexed { index, option ->
				val enabled = option.refusal == null
				val selected = option.id == chosen && enabled
				GroupRow(
					if (sideBySide) 0 else index, if (sideBySide) 1 else options.size, selected = selected,
					modifier = Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.RadioButton) { onChoose(option.id) },
				) {
					RadioButton(selected = selected, enabled = enabled, onClick = null)
					Column(Modifier.weight(1f)) {
						Text(option.label, style = MaterialTheme.typography.titleMedium, color = if (enabled) lucent.ink else lucent.ink2)
						val description = option.refusal ?: listOfNotNull(option.resolution, option.bytes?.let { fileSize(it) }).joinToString(" · ")
						if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
						val basis = if (option.refusal == null) option.basis?.replaceFirstChar { it.uppercase() } else null
						if (basis != null) Text(basis, style = MaterialTheme.typography.bodySmall, color = lucent.ink3)
					}
				}
			}
		}
	}
}

package com.nphil.marquee.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.DvrAiring
import com.nphil.marquee.core.DvrMark
import com.nphil.marquee.core.DvrOption
import com.nphil.marquee.core.DvrSetting
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.live.DvrModel
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

@Composable
internal fun DvrProgrammeActions(model: DvrModel, channel: String, programme: GuideProgramme, now: Double) {
	val airing = model.airing(channel, programme.start)
	val requested = model.requested(channel, programme)
	val recordable = airing == null && !requested && programme.guid.isNotBlank() && programme.end > now
	val loadOptions = recordable && model.canRecord
	var actionError by remember(programme.id) { mutableStateOf<String?>(null) }
	var selected by remember(programme.id) { mutableStateOf(false) }
	var cancelling by remember(programme.id) { mutableStateOf<DvrAiring?>(null) }
	when {
		airing != null -> {
			DvrNotice(airing.mark.label, isError = airing.mark == DvrMark.Failed)
			if (airing.isUpcoming && model.canRecord) {
				DvrButton(if (airing.isRecording) "Stop recording" else "Cancel airing", !model.working) {
					actionError = null
					cancelling = airing
				}
			}
		}
		requested -> DvrNotice("Recording requested", isError = false)
		loadOptions -> DvrButton("Record…", !model.working) { selected = true }
		recordable -> {
			val problem = model.scheduleError
			DvrNotice(problem ?: "Checking recording options…", isError = problem != null)
		}
	}
	if (!selected) actionError?.let { DvrNotice(it) }
	if (selected) RecordEditor(model, programme, channel, null) { selected = false }
	cancelling?.let { target ->
		DvrCancelDialog(model, target, dismiss = { cancelling = null }) { deletingSeries ->
			cancelling = null
			model.unschedule(target, deletingSeries) { actionError = it }
		}
	}
}

@Composable
internal fun DvrCancelDialog(model: DvrModel, airing: DvrAiring, dismiss: () -> Unit, confirm: (Boolean) -> Unit) {
	val series = model.schedule?.rules?.any { it.id == airing.ruleID && it.isSeries } == true
	var deletingSeries by remember(airing.id) { mutableStateOf(false) }
	val action = if (airing.isRecording) "Stop recording" else if (series) "Skip airing" else "Cancel recording"
	DvrConfirm(action, busy = model.working, dismiss = dismiss, confirm = { confirm(series && deletingSeries) }) {
		Text(airing.title)
		if (airing.isRecording && series) DvrCheck("Also delete the series rule", deletingSeries) { deletingSeries = it }
	}
}

@Composable
internal fun DvrConfirm(action: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit, content: @Composable () -> Unit) {
	AlertDialog(
		onDismissRequest = dismiss,
		containerColor = lucent.sheet,
		title = { Text("$action?") },
		text = { Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) { content() } },
		confirmButton = {
			TextButton(confirm, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = lucent.danger)) { Text(action) }
		},
		dismissButton = { TextButton(dismiss) { Text("Keep") } },
	)
}

@Composable
private fun DvrCheck(label: String, checked: Boolean, set: (Boolean) -> Unit) {
	Row(
		Modifier.fillMaxWidth().heightIn(min = Size.target).toggleable(checked, role = Role.Checkbox, onValueChange = set),
		horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically,
	) {
		Checkbox(checked, onCheckedChange = null)
		Text(label, Modifier.weight(1f))
	}
}

@Composable
private fun DvrButton(label: String, enabled: Boolean, action: () -> Unit) {
	FilledTonalButton(action, Modifier.fillMaxWidth().heightIn(min = Size.target), enabled = enabled) { Text(label) }
}

@Composable
internal fun DvrNotice(message: String, isError: Boolean = true) {
	Text(message, style = MaterialTheme.typography.bodyMedium, color = if (isError) lucent.danger else lucent.ink2)
}

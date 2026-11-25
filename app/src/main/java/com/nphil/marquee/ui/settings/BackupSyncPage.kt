package com.nphil.marquee.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.backup.BackupCenter
import com.nphil.marquee.backup.BackupPlatform
import com.nphil.marquee.container
import com.nphil.marquee.core.backupMessage
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings › Backup & Sync (DESIGN §8.13): the same rows and words as the iPhone's page. */
@Composable
internal fun BackupSyncPage(padding: PaddingValues) {
	val context = LocalContext.current
	val container = context.container
	val backup = container.backup
	val state by backup.state.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()
	var settingPassphrase by remember { mutableStateOf(false) }
	var removingPassphrase by remember { mutableStateOf(false) }
	val noAccount by produceState("") { value = container.core.backupMessage("noAccount") }

	LaunchedEffect(backup) { backup.refresh() }
	LaunchedEffect(state.hasPendingOpen) { if (state.hasPendingOpen) backup.takePendingOpen() }

	val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { backup.exported(it) }
	LaunchedEffect(state.exportName) { state.exportName?.let { save.launch(it) } }
	val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(backup::restore) }
	val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { backup.chooseFolder(it) }
	val idle = state.working == null

	BrowseList(
		modifier = Modifier.fillMaxSize(),
		contentPadding = padding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item { SettingsActionRow(0, 3, R.drawable.ic_download, "Back Up Now", enabled = idle && state.account != null, busy = state.working != null, onClick = backup::backUpNow) }
		item { SettingsActionRow(1, 3, R.drawable.ic_restart_alt, "Restore from Backup…", enabled = idle && state.account != null, onClick = { pick.launch(arrayOf("*/*")) }) }
		item { ValueRow(2, 3, "Last Backup", state.last?.let(::formatted) ?: "Never") }
		item {
			val note = when {
				state.problem != null -> null
				state.account == null -> noAccount
				state.working != null -> state.working + "…"
				else -> "One encrypted file with the settings, themes, tab layout, integrations and their keys, and paired TVs. The Plex sign-in, downloads and caches are not in it. It can be saved anywhere the file picker reaches: Google Drive, a network share, a folder on this device."
			}
			if (note != null) SettingsFootnote(note) else ErrorLine(state.problem.orEmpty(), Modifier.padding(horizontal = Space.s4))
		}

		item { Heading("Automatic") }
		val rows = if (state.automatic) 4 else 1
		item {
			SettingsSwitchRow(0, rows, "Automatic Backup", state.automatic, enabled = idle) { on ->
				if (!on) {
					backup.stopAutomatic()
				} else {
					scope.launch { if (backup.prepareAutomatic()) folder.launch(null) }
				}
			}
		}
		if (state.automatic) {
			item { ValueRow(1, rows, "Folder", state.folderName.orEmpty()) }
			item { ValueRow(2, rows, "Last Saved", state.lastAutomatic?.let(::formatted) ?: "Not yet") }
			item { SettingsActionRow(3, rows, R.drawable.ic_folder_open, "Choose Another Folder…", enabled = idle, onClick = { folder.launch(null) }) }
		}
		item {
			SettingsFootnote(
				if (state.automatic) {
					"The backup in the folder is replaced a short while after a setting changes. The copy before it stays beside it as “Marquee (previous).marqueebackup”."
				} else {
					"Keeps one backup up to date in a folder you choose."
				},
			)
		}
		state.automaticProblem?.let { item { ErrorLine(it, Modifier.padding(horizontal = Space.s4)) } }
		state.foundNewer?.let { newer ->
			item { SettingsActionRow(0, 2, R.drawable.ic_restart_alt, "Restore That Backup", enabled = idle, onClick = backup::restoreFromFolder) }
			item { SettingsActionRow(1, 2, R.drawable.ic_refresh, "Replace It with This Device’s Settings", enabled = idle, onClick = backup::replaceNewerInFolder) }
			item { SettingsFootnote("The folder holds a backup from ${formatted(newer)}, newer than anything this device saved. Automatic backup waits until one of these is chosen.") }
		}

		item { Heading("Recovery") }
		val recovery = if (state.hasPassphrase) 3 else 2
		item { ValueRow(0, recovery, "Recovery Passphrase", if (state.hasPassphrase) "Set" else "Not Set") }
		item {
			SettingsActionRow(1, recovery, R.drawable.ic_edit, if (state.hasPassphrase) "Change Passphrase…" else "Set Passphrase…", enabled = idle && state.account != null, onClick = { settingPassphrase = true })
		}
		if (state.hasPassphrase) {
			item { SettingsActionRow(2, recovery, R.drawable.ic_delete, "Remove Passphrase", enabled = idle, onClick = { removingPassphrase = true }, tint = lucent.danger) }
		}
		item {
			SettingsFootnote("The key that seals a backup stays on this device. The passphrase opens a backup on another device, or on an iPhone: a backup can only be restored there with it. A forgotten passphrase cannot be recovered.")
		}
	}

	if (settingPassphrase) {
		PassphraseDialog(backup, onDone = { settingPassphrase = false })
	}
	if (removingPassphrase) {
		AlertDialog(
			onDismissRequest = { removingPassphrase = false },
			title = { Text("Remove the recovery passphrase?") },
			text = { Text("Backups already saved still open with it. Backups made from now on open only on this device, and automatic backup stops.") },
			confirmButton = { TextButton(onClick = { backup.removePassphrase(); removingPassphrase = false }) { Text("Remove", color = lucent.danger) } },
			dismissButton = { TextButton(onClick = { removingPassphrase = false }) { Text("Cancel") } },
			containerColor = lucent.canvas,
		)
	}
	state.restore?.let { RestoreDialog(backup, it) }
}

@Composable
private fun ValueRow(index: Int, count: Int, label: String, value: String) {
	GroupRow(index, count) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Text(value, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
	}
}

/** Sets or changes the passphrase; the two fields must match before Core checks its length. */
@Composable
private fun PassphraseDialog(backup: BackupCenter, onDone: () -> Unit) {
	// Not kept across a rotation: a passphrase has no place in saved state.
	var phrase by remember { mutableStateOf("") }
	var again by remember { mutableStateOf("") }
	var problem by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val current by backup.state.collectAsStateWithLifecycle()
	AlertDialog(
		onDismissRequest = onDone,
		title = { Text("Recovery Passphrase") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
				SettingsFieldRow(0, 2, "Passphrase", phrase, hint = "At least 12 characters", onChange = { phrase = it; problem = null }, onFocus = {}, secret = true)
				SettingsFieldRow(1, 2, "Repeat", again, hint = "Same again", onChange = { again = it; problem = null }, onFocus = {}, secret = true, imeAction = ImeAction.Done)
				problem?.let { ErrorLine(it, Modifier.padding(top = Space.s2)) }
				Text(
					"It opens a backup on another device, or on an iPhone. Marquee cannot recover it if it is forgotten. A few random words work well.",
					style = MaterialTheme.typography.bodySmall,
					color = lucent.ink2,
					modifier = Modifier.padding(top = Space.s2),
				)
			}
		},
		confirmButton = {
			TextButton(
				enabled = phrase.isNotEmpty() && current.working == null,
				onClick = {
					if (phrase != again) {
						problem = "The two passphrases are not the same."
					} else {
						scope.launch {
							problem = backup.setPassphrase(phrase)
							if (problem == null) onDone()
						}
					}
				},
			) { Text("Save") }
		},
		dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
		containerColor = lucent.canvas,
	)
}

/** The restore, step by step: reading, the passphrase when the file needs one, the review, then the result. */
@Composable
private fun RestoreDialog(backup: BackupCenter, restore: BackupCenter.Restore) {
	var confirming by remember { mutableStateOf(false) }
	var phrase by remember(restore) { mutableStateOf("") }
	val working = restore is BackupCenter.Restore.Reading || restore is BackupCenter.Restore.Applying
	AlertDialog(
		onDismissRequest = { if (!working && restore !is BackupCenter.Restore.Done) backup.closeRestore() },
		title = { Text("Restore") },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				when (restore) {
					BackupCenter.Restore.Reading -> Progress("Reading the backup…")
					BackupCenter.Restore.Applying -> Progress("Restoring…")
					is BackupCenter.Restore.Passphrase -> {
						Text(restore.message, color = lucent.ink2)
						SettingsFieldRow(0, 1, "Passphrase", phrase, hint = "Passphrase", onChange = { phrase = it }, onFocus = {}, secret = true, imeAction = ImeAction.Done, onDone = { if (phrase.isNotEmpty()) backup.submitPassphrase(phrase) })
					}
					is BackupCenter.Restore.Review -> Review(restore)
					BackupCenter.Restore.Done -> Text("The settings are restored. Marquee has to close and open again to start from them.", color = lucent.ink2)
					is BackupCenter.Restore.Failed -> Text(restore.message, color = lucent.ink2)
				}
			}
		},
		confirmButton = {
			when (restore) {
				is BackupCenter.Restore.Passphrase -> TextButton(enabled = phrase.isNotEmpty(), onClick = { backup.submitPassphrase(phrase) }) { Text("Open Backup") }
				is BackupCenter.Restore.Review -> if (restore.lines.isNotEmpty()) TextButton(onClick = { confirming = true }) { Text("Restore…") }
				BackupCenter.Restore.Done -> TextButton(onClick = backup::closeApp) { Text("Close Marquee") }
				is BackupCenter.Restore.Failed -> TextButton(onClick = backup::closeRestore) { Text("Close") }
				else -> Unit
			}
		},
		dismissButton = {
			if (restore is BackupCenter.Restore.Reading || restore is BackupCenter.Restore.Passphrase || restore is BackupCenter.Restore.Review) {
				TextButton(onClick = backup::closeRestore) { Text("Cancel") }
			}
		},
		containerColor = lucent.canvas,
	)
	if (confirming) {
		AlertDialog(
			onDismissRequest = { confirming = false },
			title = { Text("Restore this backup?") },
			text = { Text("The settings it holds replace the ones on this device. If anything fails, nothing is changed.") },
			confirmButton = { TextButton(onClick = { confirming = false; backup.confirmRestore() }) { Text("Restore", color = lucent.danger) } },
			dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
			containerColor = lucent.canvas,
		)
	}
}

@Composable
private fun Progress(text: String) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		CircularProgressIndicator(Modifier.size(28.dp))
		Text(text, color = lucent.ink2)
	}
}

@Composable
private fun Review(review: BackupCenter.Restore.Review) {
	val summary = review.summary
	val same = summary.platform == BackupPlatform.ANDROID
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		ValueRow(0, 2, "Made", formatted(summary.createdAt))
		ValueRow(1, 2, "Made On", "${if (same) "Android" else "iPhone"} · Marquee ${summary.appVersion}")
	}
	if (review.lines.isEmpty()) {
		Text("Nothing in this backup applies to this device.", color = lucent.ink2)
		return
	}
	Heading(if (same) "Restores" else "Restores, from an iPhone")
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		review.lines.forEachIndexed { index, line ->
			GroupRow(index, review.lines.size) {
				Text(line.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
				line.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2) }
			}
		}
	}
	SettingsFootnote(
		if (same) {
			"These replace what this device has now. Marquee closes afterwards and starts again from them."
		} else {
			"Only the integrations are portable between Android and iPhone. Everything else on this device stays as it is. Marquee closes afterwards and starts again."
		},
	)
}

private fun formatted(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(seconds * 1000))

/** Not a type any provider knows, so the file keeps the name it was offered. */
private const val BACKUP_MIME = "application/octet-stream"

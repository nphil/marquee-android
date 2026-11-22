package com.nphil.marquee.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nphil.marquee.R
import com.nphil.marquee.downloads.DownloadDisk
import com.nphil.marquee.downloads.DownloadEntry
import com.nphil.marquee.downloads.DownloadPhase
import com.nphil.marquee.downloads.DownloadQuality
import com.nphil.marquee.downloads.Downloads
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.components.ChoiceRow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.plusInset
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import java.io.File
import kotlin.math.roundToInt

private val DownloadColumnMax = 620.dp
private val DownloadGridMax = 1080.dp

@Composable
internal fun DownloadsScreen(downloads: Downloads, onBack: (() -> Unit)?) {
	val records by downloads.records.collectAsStateWithLifecycle()
	val preferences by downloads.preferences.collectAsStateWithLifecycle()
	val verifyProfile by downloads.needsProfileVerification.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	Scaffold(
		topBar = {
			TopAppBar(title = { Text("Downloads") }, navigationIcon = {
				if (onBack != null) IconButton(onClick = onBack) {
					Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
				}
			})
		},
		containerColor = lucent.canvas,
	) { padding ->
		DownloadsContent(
			records = records, metered = preferences.metered, verifyProfile = verifyProfile, padding = padding.plusInset(),
			poster = downloads::poster,
			onPlay = { navigator?.openDownload(it) },
			onPause = { downloads.pause(it) }, onResume = { downloads.resume(it) }, onRemove = { downloads.remove(it) },
		)
	}
}

@Composable
internal fun DownloadsContent(
	records: List<DownloadEntry>,
	metered: Boolean,
	verifyProfile: Boolean,
	padding: PaddingValues,
	poster: (DownloadEntry) -> File,
	onPlay: (DownloadEntry) -> Unit,
	onPause: (DownloadEntry) -> Unit,
	onResume: (DownloadEntry) -> Unit,
	onRemove: (DownloadEntry) -> Unit,
) {
	var removing by rememberSaveable { mutableStateOf<String?>(null) }
	val gridState = rememberLazyGridState()
	val verticalPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
	BoxWithConstraints(
		Modifier.fillMaxSize().downloadSidePadding(padding),
		contentAlignment = Alignment.TopCenter,
	) {
		if (records.isEmpty()) {
			Column(
				Modifier.fillMaxSize().padding(verticalPadding).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
			) {
				if (verifyProfile) EmptyState(R.drawable.ic_download, "Reconnect to Plex", "Connect once to verify this profile. Downloads stay on this device.")
				else EmptyState(R.drawable.ic_download, "No Downloads", "Choose Download on a movie or episode to watch it without a connection.")
			}
		} else {
			val largeText = LocalDensity.current.fontScale >= 1.5f
			val minCell = if (largeText) 480.dp else 360.dp
			val columns = if (maxWidth >= minCell * 2 + Space.s3) 2 else 1
			val stacked = largeText || maxWidth < 320.dp
			LazyVerticalGrid(
				columns = GridCells.Fixed(columns),
				modifier = Modifier.widthIn(max = if (columns == 2) DownloadGridMax else DownloadColumnMax).fillMaxWidth(),
				state = gridState,
				contentPadding = verticalPadding,
				verticalArrangement = Arrangement.spacedBy(Space.s3),
				horizontalArrangement = Arrangement.spacedBy(Space.s3),
			) {
				items(records, key = { it.id }) { entry ->
					DownloadRow(
						entry, if (entry.assetsFetched) poster(entry) else null, metered,
						stacked = stacked,
						onPlay = { onPlay(entry) }, onPause = { onPause(entry) },
						onResume = { onResume(entry) }, onRemove = { removing = entry.id },
					)
				}
			}
		}
	}
	val selected = records.firstOrNull { it.id == removing }
	if (selected != null) {
		AlertDialog(
			onDismissRequest = { removing = null }, title = { Text("Remove download?") },
			modifier = Modifier.downloadDialogWidth(), properties = DialogProperties(usePlatformDefaultWidth = false),
			text = {
				Text(
					"The saved copy of ${selected.title.title} will be removed from this device, not from Plex.",
					modifier = Modifier.verticalScroll(rememberScrollState()),
				)
			},
			confirmButton = { TextButton(onClick = { removing = null; onRemove(selected) }) { Text("Remove", color = lucent.danger) } },
			dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
		)
	}
}

@Composable
private fun DownloadRow(
	entry: DownloadEntry, poster: File?, metered: Boolean, stacked: Boolean,
	onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit,
) {
	if (entry.phase == DownloadPhase.Removed) return
	val ready = entry.phase == DownloadPhase.Ready
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(lucent.sheet)
			.then(if (ready) Modifier.clickable(role = Role.Button, onClickLabel = "Play download", onClick = onPlay) else Modifier)
			.padding(horizontal = Space.s4, vertical = Space.s3),
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		if (stacked) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
				DownloadPoster(poster, ready)
				Row { DownloadControls(entry, onPause, onResume, onRemove) }
			}
			DownloadDetails(entry, metered, Modifier.fillMaxWidth())
		} else {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
				DownloadPoster(poster, ready)
				DownloadDetails(entry, metered, Modifier.weight(1f))
				Column { DownloadControls(entry, onPause, onResume, onRemove) }
			}
		}
	}
}

@Composable
private fun DownloadPoster(poster: File?, ready: Boolean) {
	Box(
		Modifier.size(width = 52.dp, height = 78.dp).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff),
		contentAlignment = Alignment.Center,
	) {
		if (!ready) Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = lucent.ink2)
		if (poster != null) AsyncImage(poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
		if (ready) {
			Box(
				Modifier.size(Space.s7).background(lucent.canvas, CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, tint = lucent.ink)
			}
		}
	}
}

@Composable
private fun DownloadDetails(entry: DownloadEntry, metered: Boolean, modifier: Modifier) {
	val ready = entry.phase == DownloadPhase.Ready
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
		Text(entry.title.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		val group = listOfNotNull(entry.title.show, entry.title.episodeCode, entry.title.year?.toString()?.takeIf { entry.title.show == null })
		if (group.isNotEmpty()) Text(group.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
		val format = listOfNotNull(entry.choice.label, entry.choice.resolution, entry.length?.takeIf { ready }?.let { fileSize(it) })
		Text(format.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		val fraction = entry.fraction
		val progress = fraction?.let { " · ${(it * 100).roundToInt()}%" }
			?: entry.downloaded.takeIf { it > 0 }?.let { " · ${fileSize(it)} saved" }.orEmpty()
		val status = when (entry.phase) {
			DownloadPhase.Queued -> (if (metered) "Waiting to download" else "Waiting for Wi-Fi or its turn") + progress
			DownloadPhase.Converting -> "Preparing on Plex$progress"
			DownloadPhase.Downloading -> "Downloading$progress"
			DownloadPhase.Paused -> if (entry.submissionPending) "Plex may already be preparing this copy" else "Paused$progress"
			DownloadPhase.Failed -> entry.failure ?: "Download failed"
			DownloadPhase.Ready -> when {
				entry.watch.offset > 0 -> "Resume at ${entry.watch.offset / 60_000} min"
				entry.watch.watched -> "Watched · Saved offline"
				else -> "Saved offline"
			}
			DownloadPhase.Removed -> return@Column
		}
		Text(status, style = MaterialTheme.typography.bodySmall, color = if (entry.phase == DownloadPhase.Failed) lucent.danger else lucent.ink2)
		if (entry.phase.active && entry.phase != DownloadPhase.Queued) {
			if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = lucent.accent, trackColor = lucent.trackOff)
			else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = lucent.accent, trackColor = lucent.trackOff)
		}
	}
}

@Composable
private fun DownloadControls(entry: DownloadEntry, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
	when {
		entry.phase.active -> IconButton(onClick = onPause) {
			Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause download", tint = lucent.ink)
		}
		!entry.submissionPending && (entry.phase == DownloadPhase.Paused || entry.phase == DownloadPhase.Failed) -> IconButton(onClick = onResume) {
			Icon(painterResource(R.drawable.ic_refresh), contentDescription = if (entry.phase == DownloadPhase.Paused) "Resume download" else "Retry download", tint = lucent.ink)
		}
	}
	IconButton(onClick = onRemove) {
		Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove download", tint = lucent.ink2)
	}
}

@Composable
internal fun DownloadSettingsPage(downloads: Downloads, padding: PaddingValues, openDownloads: () -> Unit) {
	val prefs by downloads.preferences.collectAsStateWithLifecycle()
	val records by downloads.records.collectAsStateWithLifecycle()
	val completed = records.count { it.phase == DownloadPhase.Ready }
	val disk by produceState<DownloadDisk?>(null, downloads, completed, records.size) { value = downloads.usage() }
	Box(Modifier.fillMaxSize().downloadSidePadding(padding), contentAlignment = Alignment.TopCenter) {
		LazyColumn(
			Modifier.widthIn(max = DownloadColumnMax).fillMaxWidth(),
			contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
			verticalArrangement = Arrangement.spacedBy(GroupGap),
		) {
			item {
				ChoiceRow("Default quality", prefs.quality, DownloadQuality.entries, DownloadQuality::label, 0, 3) { quality ->
					downloads.changePreferences { it.copy(quality = quality) }
				}
			}
			item {
				DownloadSwitch("Download over metered connections", "Includes mobile data", 1, 3, prefs.metered) {
					downloads.changePreferences { it.copy(metered = !it.metered) }
				}
			}
			item {
				DownloadSwitch("Remove after watching", "Removes the file after Plex receives the watch progress", 2, 3, prefs.autoDelete) {
					downloads.changePreferences { it.copy(autoDelete = !it.autoDelete) }
				}
			}
			item {
				GroupRow(0, 1, modifier = Modifier.padding(top = Space.s4), onClick = openDownloads) {
					Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = lucent.ink2)
					Column(Modifier.weight(1f)) {
						Text("Manage Downloads", style = MaterialTheme.typography.titleMedium, color = lucent.ink)
						disk?.let {
							Text("${fileSize(it.downloads)} used · ${fileSize(it.available)} available", style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun DownloadSwitch(label: String, note: String, index: Int, count: Int, checked: Boolean, change: () -> Unit) {
	GroupRow(index, count, modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = { change() })) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			Text(note, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2)
		}
		Switch(checked = checked, onCheckedChange = null)
	}
}

@Composable
private fun Modifier.downloadSidePadding(padding: PaddingValues): Modifier {
	val direction = LocalLayoutDirection.current
	return this.padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
}

@Composable
internal fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

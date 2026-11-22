package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.DirectPlayMode
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.data.PlaybackPrefs
import com.nphil.marquee.playback.rememberPlaybackPrefs
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Video & Playback (DESIGN §8.7): how titles stream, the hero trailer, and a show's theme music with its volume.
 * Saved for the active profile and applied at once.
 */
@Composable
fun VideoPlaybackPage(contentPadding: PaddingValues) {
	val container = LocalContext.current.container
	val profile by remember(container) {
		container.session.state.map { it.user?.uuid }.distinctUntilChanged()
	}.collectAsStateWithLifecycle(null)
	val prefs = rememberPlaybackPrefs().value ?: return
	// The slider follows the finger; the setting is saved when it lets go.
	val volume = rememberSliderState(prefs.themeMusicVolume)
	// Written on the app's scope, so a change made as the page closes is still saved.
	fun change(edit: (PlaybackPrefs) -> PlaybackPrefs) {
		val id = profile ?: return
		container.scope.launch { container.settings.updatePlayback(id, edit) }
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "quality") {
			ChoiceRow("Streaming Quality", prefs.quality, QualityPreset.all, QualityPreset::title, index = 0, count = 3) { quality ->
				change { it.copy(quality = quality) }
			}
		}
		item(key = "savesData") {
			SwitchRow("Save Data on Metered Networks", prefs.savesData, index = 1, count = 3) { on -> change { it.copy(savesData = on) } }
		}
		item(key = "directPlay") {
			ChoiceRow("Direct Play", prefs.directPlay, DirectPlayMode.entries, ::directPlayTitle, index = 2, count = 3) { mode ->
				change { it.copy(directPlay = mode) }
			}
		}
		item(key = "streamingNote") {
			Footnote(
				"Auto picks a quality for the connection and changes it as the connection does. A lower quality has the " +
					"server convert the video. Direct Play plays a file as it is when the tablet can: Always tries it even " +
					"where the tablet may not cope, Never has the server convert every title.",
			)
		}
		item(key = "trailers") {
			SwitchRow("Play Trailers Automatically", prefs.playsTrailers) { on -> change { it.copy(playsTrailers = on) } }
		}
		item(key = "trailersNote") {
			Footnote(
				"After a moment on a title, play its trailer with sound. It waits while Battery Saver or a metered " +
					"connection is on or animations are turned off, and plays silently over other audio.",
			)
		}
		item(key = "themeMusic") {
			SwitchRow("Play Theme Music", prefs.playsThemeMusic, index = 0, count = 2) { on -> change { it.copy(playsThemeMusic = on) } }
		}
		item(key = "volume") {
			GroupRow(index = 1, count = 2) {
				Icon(painterResource(R.drawable.ic_volume_off), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(24.dp))
				Slider(
					state = volume,
					onValueChange = { volume.value = it },
					onValueChangeFinished = { change { it.copy(themeMusicVolume = volume.value) } },
					enabled = prefs.playsThemeMusic,
					modifier = Modifier.weight(1f).semantics { contentDescription = "Theme Music Volume" },
				)
				Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(24.dp))
			}
		}
		item(key = "themeMusicNote") {
			Footnote(
				"Plays on a show's page and its seasons', fading out when the page closes or a video starts. " +
					"It waits while other audio plays.",
			)
		}
	}
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, index: Int = 0, count: Int = 1, onChange: (Boolean) -> Unit) {
	GroupRow(index, count, onClick = { onChange(!checked) }) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = null)
	}
}

/** A row that shows its choice and opens a menu of the [options]. */
@Composable
private fun <T> ChoiceRow(label: String, value: T, options: List<T>, title: (T) -> String, index: Int, count: Int, onChange: (T) -> Unit) {
	var open by remember { mutableStateOf(false) }
	GroupRow(index, count, onClick = { open = true }) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Box {
			Text(title(value), style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
			DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
				options.forEach { option ->
					DropdownMenuItem(
						text = { Text(title(option)) },
						onClick = {
							open = false
							onChange(option)
						},
					)
				}
			}
		}
	}
}

private fun directPlayTitle(mode: DirectPlayMode): String = when (mode) {
	DirectPlayMode.Auto -> "Auto"
	DirectPlayMode.Force -> "Always"
	DirectPlayMode.Disabled -> "Never"
}

@Composable
private fun Footnote(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = lucent.ink2,
		modifier = Modifier.padding(horizontal = Space.s4).padding(top = Space.s2, bottom = Space.s5),
	)
}

package com.nphil.marquee.ui.link

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.link.LinkNowPlaying
import com.nphil.marquee.link.LinkStatus
import com.nphil.marquee.link.LinkWire
import com.nphil.marquee.link.LinkedPhones
import com.nphil.marquee.playback.rememberPlaybackPrefs
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.tvremote.RemoteButton
import com.nphil.marquee.ui.tvremote.RemoteGlyph
import com.nphil.marquee.ui.tvremote.RemotePanelBody
import com.nphil.marquee.ui.tvremote.RemoteSectionLabel
import kotlinx.coroutines.delay
import kotlin.math.min

/** Home's button for the linked phone that plays or drives a screen. */
@Composable
internal fun LinkEntryIcon(name: String, onClick: () -> Unit) {
	IconButton(onClick = onClick, modifier = Modifier.testTag("link-remote-entry")) {
		Icon(painterResource(R.drawable.ic_smartphone), "Control $name", tint = lucent.accentText)
	}
}

/**
 * This phone as the remote for a linked phone: its own player through a few buttons, and the TV or glasses it drives
 * through the arrow keys. Open while [LinkedPhones.State.controlling] names a phone.
 */
@Composable
internal fun LinkRemoteOverlay(link: LinkedPhones) {
	val state by link.state.collectAsStateWithLifecycle()
	val skip = rememberPlaybackPrefs().value?.skipInterval ?: DEFAULT_SKIP
	val peer = state.controlling?.let { state.phone(it) } ?: return
	Dialog(onDismissRequest = link::stopControlling, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		RemotePanelBody(peer.name, link::stopControlling) {
			val presence = state.presence[peer.id]
			val status = presence?.status
			when {
				presence == null -> Text("Not connected. Keep both phones on the same Wi-Fi, with Marquee open on both.", color = lucent.ink2)
				status == null -> CircularProgressIndicator(Modifier.size(Space.s6), color = lucent.accent)
				else -> {
					if (status.screen != null) Screen(status, link::send)
					status.nowPlaying?.let { Player(it, presence.statusAt, skip, link::send) }
					status.volume?.let { Volume(it, link::send) }
				}
			}
		}
	}
}

/** The big screen the other phone drives, with its focused item, and the keys that move around it. */
@Composable
private fun Screen(status: LinkStatus, send: (LinkWire) -> Unit) {
	RemoteSectionLabel(if (status.screen == "tv") "Driving a TV" else "Driving XR glasses")
	status.focus?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = lucent.ink) }
	status.focusDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2) }
	Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		RemoteGlyph("Up", R.drawable.ic_expand_more, turn = 180f, onClick = { send(LinkWire.move("up")) })
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
			RemoteGlyph("Left", R.drawable.ic_chevron_right, turn = 180f, onClick = { send(LinkWire.move("left")) })
			RemoteGlyph("Select", R.drawable.ic_check, emphasized = true, onClick = { send(LinkWire.input("select")) })
			RemoteGlyph("Right", R.drawable.ic_chevron_right, onClick = { send(LinkWire.move("right")) })
		}
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
			RemoteGlyph("Back", R.drawable.ic_arrow_back, onClick = { send(LinkWire.input("back")) })
			RemoteGlyph("Down", R.drawable.ic_expand_more, onClick = { send(LinkWire.move("down")) })
		}
	}
}

/** The other phone's own player: play and pause, skip, next and subtitles, with where it is. */
@Composable
private fun Player(playing: LinkNowPlaying, statusAt: Long, skip: Int, send: (LinkWire) -> Unit) {
	// Time runs on from the last status while it plays, up to the end.
	val position by produceState(playing.position, playing, statusAt) {
		while (true) {
			val moved = playing.position + if (playing.isPlaying) (SystemClock.elapsedRealtime() - statusAt) / 1000.0 else 0.0
			value = if (playing.duration > 0) min(moved, playing.duration) else moved
			delay(PROGRESS_MS)
		}
	}
	RemoteSectionLabel("Playing")
	Text(playing.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
	playing.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2) }
	if (playing.duration > 0) {
		LinearProgressIndicator(progress = { (position / playing.duration).toFloat().coerceIn(0f, 1f) },
			modifier = Modifier.fillMaxWidth(), color = lucent.accent, trackColor = lucent.glass)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(clock(position), style = MaterialTheme.typography.bodySmall, color = lucent.ink3)
			Text(clock(playing.duration), style = MaterialTheme.typography.bodySmall, color = lucent.ink3)
		}
	}
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
		RemoteGlyph("Back $skip seconds", R.drawable.ic_replay, onClick = { send(LinkWire.command("skip", seconds = -skip.toDouble())) })
		RemoteGlyph(if (playing.isPlaying) "Pause" else "Play", if (playing.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
			emphasized = true, onClick = { send(LinkWire.command("playPause")) })
		RemoteGlyph("Forward $skip seconds", R.drawable.ic_forward, onClick = { send(LinkWire.command("skip", seconds = skip.toDouble())) })
		if (playing.hasNext) RemoteGlyph("Next episode", R.drawable.ic_skip_next, onClick = { send(LinkWire.command("next")) })
		if (playing.subtitles.isNotEmpty()) Subtitles(playing, send)
	}
}

@Composable
private fun Subtitles(playing: LinkNowPlaying, send: (LinkWire) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		RemoteGlyph("Subtitles", R.drawable.ic_closed_caption, emphasized = playing.subtitle != null, onClick = { open = true })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			SubtitleItem("Off", playing.subtitle == null) { open = false; send(LinkWire.command("subtitle")) }
			for (track in playing.subtitles) {
				SubtitleItem(track.label, playing.subtitle == track.id) { open = false; send(LinkWire.command("subtitle", track = track.id)) }
			}
		}
	}
}

@Composable
private fun SubtitleItem(label: String, selected: Boolean, onClick: () -> Unit) {
	DropdownMenuItem(
		text = { Text(label, color = lucent.ink) },
		onClick = onClick,
		leadingIcon = { if (selected) Icon(painterResource(R.drawable.ic_check), "Showing", tint = lucent.accentText) else Box(Modifier.size(24.dp)) },
	)
}

/** The volume of the phone that plays, which this phone changes a step at a time. */
@Composable
private fun Volume(level: Double, send: (LinkWire) -> Unit) {
	RemoteSectionLabel("Volume")
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
		RemoteButton("Quieter", onClick = { send(LinkWire.command("volume", step = -1)) })
		LinearProgressIndicator(progress = { level.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f), color = lucent.accent,
			trackColor = lucent.glass)
		RemoteButton("Louder", onClick = { send(LinkWire.command("volume", step = 1)) })
	}
}

private fun clock(seconds: Double): String {
	val total = seconds.toLong().coerceAtLeast(0)
	val (hours, minutes, rest) = Triple(total / 3600, total / 60 % 60, total % 60)
	return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private const val DEFAULT_SKIP = 10
private const val PROGRESS_MS = 250L

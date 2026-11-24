package com.nphil.marquee.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MimeTypes
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.CreditsAutoplay
import com.nphil.marquee.core.DirectPlayMode
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.core.SkipTapMode
import com.nphil.marquee.data.PlaybackPrefs
import com.nphil.marquee.live.canReadLocalTuner
import com.nphil.marquee.playback.DeviceDecoders
import com.nphil.marquee.playback.rememberPlaybackPrefs
import com.nphil.marquee.ui.components.ChoiceRow
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Video & Playback (DESIGN §8.7): how titles stream, the hero trailer, and a show's theme music with its volume.
 * Saved for the active profile and applied at once.
 */
@Composable
fun VideoPlaybackPage(contentPadding: PaddingValues) {
	val context = LocalContext.current
	val container = context.container
	var tunerPermission by remember(context) { mutableStateOf(canReadLocalTuner(context)) }
	val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		tunerPermission = canReadLocalTuner(context)
	}
	LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tunerPermission = canReadLocalTuner(context) }
	// The codec list takes tens of milliseconds to read, so it is asked off the main thread.
	val decodesMpeg2 by produceState(true) {
		value = withContext(Dispatchers.Default) { DeviceDecoders.installed.canDecode(MimeTypes.VIDEO_MPEG2) }
	}
	val softMpeg2 by produceState(false) {
		value = withContext(Dispatchers.Default) { DeviceDecoders.installed.platformLacks(MimeTypes.VIDEO_MPEG2) }
	}
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

	BrowseList(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "skipInterval") {
			ChoiceRow("Skip Interval", prefs.skipInterval, PlaybackPrefs.SKIP_INTERVALS, ::seconds, index = 0, count = 2) { interval ->
				change { it.copy(skipInterval = interval) }
			}
		}
		item(key = "skipTaps") {
			ChoiceRow("Skip Gesture", prefs.skipTaps, SkipTapMode.entries, ::skipTapsTitle, index = 1, count = 2) { mode ->
				change { it.copy(skipTaps = mode) }
			}
		}
		item(key = "skipNote") {
			Footnote(
				"Skip back and skip forward jump by this much. Double-tap the left or right of the picture to skip. " +
					"With Double-Tap, a single tap always shows or hides the controls.",
			)
		}
		item(key = "introSkip") {
			ChoiceRow("Skip Intro", prefs.introSkipDelay, listOf(0) + PlaybackPrefs.COUNTDOWNS, ::introSkipTitle, index = 0, count = 1) { delay ->
				change { it.copy(introSkipDelay = delay) }
			}
		}
		item(key = "introNote") { Footnote("Intro countdowns start only at intro markers from Plex.") }
		val autoplayRows = if (!prefs.autoplaysNext) 1 else if (prefs.creditsAutoplay == CreditsAutoplay.Countdown) 3 else 2
		item(key = "autoplay") {
			SwitchRow("Autoplay Next Episode", prefs.autoplaysNext, index = 0, count = autoplayRows) { on -> change { it.copy(autoplaysNext = on) } }
		}
		if (prefs.autoplaysNext) {
			item(key = "credits") {
				ChoiceRow("Credits", prefs.creditsAutoplay, CreditsAutoplay.entries, ::creditsTitle, index = 1, count = autoplayRows) { mode ->
					change { it.copy(creditsAutoplay = mode) }
				}
			}
			if (prefs.creditsAutoplay == CreditsAutoplay.Countdown) {
				item(key = "countdown") {
					ChoiceRow("Countdown", prefs.nextEpisodeDelay, PlaybackPrefs.COUNTDOWNS, ::seconds, index = 2, count = autoplayRows) { delay ->
						change { it.copy(nextEpisodeDelay = delay) }
					}
				}
			}
		}
		item(key = "autoplayNote") { Footnote(autoplayNote(prefs)) }
		item(key = "background") {
			SwitchRow("Keep Playing in Background", prefs.keepsPlaying) { on -> change { it.copy(keepsPlaying = on) } }
		}
		item(key = "backgroundNote") {
			Footnote(
				"Audio carries on with the screen off or in another app, with controls on the lock screen and in the " +
					"notifications. Off pauses the title when the player leaves the screen.",
			)
		}
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
		item(key = "directTuner") {
			SwitchRow("Direct from Tuner", prefs.readsTunerDirectly) { on ->
				change { it.copy(readsTunerDirectly = on) }
			}
		}
		if (prefs.readsTunerDirectly && !tunerPermission) {
			item(key = "tunerPermission") {
				GroupRow(index = 0, count = 1, onClick = { requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) {
					Text("Allow Local Network Access", style = MaterialTheme.typography.titleMedium, color = lucent.ink)
				}
			}
		}
		item(key = "smoothMotion") {
			SwitchRow("Prefer smooth motion", prefs.prefersSmoothMotion) { on ->
				change { it.copy(prefersSmoothMotion = on) }
			}
		}
		item(key = "smoothMotionNote") {
			Footnote(
				"Interlaced channels (1080i, 480i) play the original broadcast at about 60 pictures a second. " +
					"Off: the TV converts them to 30, which uses less battery and data.",
			)
		}
		item(key = "directTunerNote") {
			Footnote(
				if (prefs.readsTunerDirectly && !tunerPermission)
					"Allow local network access so Marquee can reach the nearby tuner. Until then, Live TV uses Plex where available."
				else if (prefs.readsTunerDirectly && !decodesMpeg2)
					"This tablet can't decode MPEG-2, the video of most antenna channels, so Live TV plays through Plex."
				else if (prefs.readsTunerDirectly && softMpeg2)
					"Live TV can play straight from a nearby tuner at home. This tablet has no MPEG-2 decoder, the video of " +
						"most antenna channels, so Marquee decodes it in software, which uses more power. Away from home, it uses Plex."
				else "Live TV can play straight from a nearby tuner at home. Away from home, it uses Plex.",
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

private fun directPlayTitle(mode: DirectPlayMode): String = when (mode) {
	DirectPlayMode.Auto -> "Auto"
	DirectPlayMode.Force -> "Always"
	DirectPlayMode.Disabled -> "Never"
}

private fun seconds(value: Int) = "$value seconds"

private fun introSkipTitle(delay: Int) = if (delay == 0) "Manually" else "After ${seconds(delay)}"

private fun skipTapsTitle(mode: SkipTapMode): String = when (mode) {
	SkipTapMode.DoubleTap -> "Double-Tap"
	SkipTapMode.Chained -> "Double-Tap, Then Keep Tapping"
}

private fun creditsTitle(mode: CreditsAutoplay): String = when (mode) {
	CreditsAutoplay.Countdown -> "Countdown"
	CreditsAutoplay.SkipInstantly -> "Skip Credits Instantly"
}

private fun autoplayNote(prefs: PlaybackPrefs): String = when {
	!prefs.autoplaysNext -> "Off: an episode ends on its own. At Plex's credits marker, Up Next is offered and waits for a tap."
	prefs.creditsAutoplay == CreditsAutoplay.Countdown ->
		"At Plex's credits marker, Up Next counts down ${prefs.nextEpisodeDelay} s, then the next episode plays. " +
			"An episode with no credits marker goes on when it ends."
	else -> "At Plex's credits marker the next episode starts at once, with no card. An episode with no credits marker goes on when it ends."
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

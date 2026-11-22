package com.nphil.marquee.playback

import android.view.LayoutInflater
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val FADE_MILLIS = 400

/**
 * The trailer playing over a title's hero art; the caller sizes it to the hero (16:9). It starts after
 * [TrailerAutoplay.dwell] while [TrailerAutoplay.decide] allows, plays once with the tablet's sound rules, fades in when
 * its first picture is on screen and out, with its sound stopped at once, when any condition closes. It is a preview:
 * no controls, no watch state, and only a file the tablet plays untouched ([DirectPlay]); with none, it shows nothing.
 * [visible] is true while the page is on top and the hero is on screen.
 */
@Composable
fun TrailerPreview(trailer: PlexItem, visible: Boolean, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val container = context.container
	val coordinator = container.playback
	val prefs by rememberPlaybackPrefs()
	val signals = rememberPlaybackSignals()
	val onTop by rememberUpdatedState(visible)
	val factory = remember(container) { PlaybackFactory(context, container.http) }

	val verdict by remember {
		derivedStateOf {
			TrailerAutoplay.decide(
				TrailerAutoplay.Conditions(
					enabled = prefs?.playsTrailers == true,
					powerSaver = signals.powerSaver,
					reduceMotion = signals.reduceMotion,
					metered = signals.metered,
					appActive = signals.appActive,
					heroVisible = onTop,
					playerOpen = coordinator.playerOpen,
					// A theme tune that is heard keeps the sound, as another app's audio does.
					otherAudioPlaying = signals.otherAudio || coordinator.themeHeard,
				),
			)
		}
	}
	// It played to its end or failed: this page does not start it again.
	var finished by remember(trailer.ratingKey) { mutableStateOf(false) }
	var run by remember { mutableStateOf<TrailerRun?>(null) }
	val active = verdict != TrailerAutoplay.Verdict.Hold && !finished
	val wantsSound = active && verdict == TrailerAutoplay.Verdict.Play(muted = false)
	val current = run
	val soundOn = wantsSound && current?.focus?.held == true

	LaunchedEffect(active, trailer.ratingKey) {
		if (!active) {
			val ending = run ?: return@LaunchedEffect
			ending.silence()
			try {
				delay(FADE_MILLIS + 50L)
			} finally {
				ending.release()
				if (run === ending) run = null
			}
			return@LaunchedEffect
		}
		run?.release()
		run = null
		delay(TrailerAutoplay.dwell)
		val part = withContext(Dispatchers.Default) { DirectPlay.part(trailer.media, DeviceDecoders.installed) }
		if (part == null) {
			Diag.info(DiagCategory.Player, "trailer.unplayable", "versions" to trailer.media.size)
			finished = true
			return@LaunchedEffect
		}
		val source = container.playbackSource(part.key)
		if (source == null) {
			finished = true
			return@LaunchedEffect
		}
		val player = factory.create(source.headers, PlaybackFactory.Kind.Trailer)
		player.setMediaItem(MediaItem.fromUri(source.url))
		player.prepare()
		player.playWhenReady = true
		run = TrailerRun(player, TrailerFocus(context)) { finished = true }
		Diag.info(DiagCategory.Player, "trailer.start", "container" to part.container)
	}
	// The focus is asked for only while the trailer is to have sound, and the sound follows the focus.
	LaunchedEffect(current, wantsSound) {
		if (wantsSound) current?.focus?.request() else current?.focus?.abandon()
	}
	LaunchedEffect(current, soundOn) { current?.setSound(soundOn) }
	DisposableEffect(current, soundOn) {
		// The tablet's own sound is not another app's: the signals subtract it.
		val hold = if (current != null && soundOn) coordinator.holdAudio() else null
		onDispose { hold?.release() }
	}
	val sounding = current != null && soundOn && current.playing
	LaunchedEffect(sounding) { coordinator.trailerSounding = sounding }
	DisposableEffect(Unit) {
		onDispose {
			coordinator.trailerSounding = false
			run?.release()
		}
	}

	val alpha by animateFloatAsState(if (current?.shown == true) 1f else 0f, tween(FADE_MILLIS), label = "trailer")
	if (current != null) {
		AndroidView(
			factory = { LayoutInflater.from(it).inflate(R.layout.trailer_player_view, null) as PlayerView },
			update = { it.player = current.player },
			modifier = modifier
				.graphicsLayer { this.alpha = alpha }
				.semantics { contentDescription = "Trailer playing" },
			onRelease = { it.player = null },
		)
	}
}

/** One trailer's player and what it shows: whether its first picture is up and whether it is playing. */
@Stable
private class TrailerRun(val player: ExoPlayer, val focus: TrailerFocus, onEnded: () -> Unit) {
	/** The first picture is on screen; the art beneath shows again when the trailer ends or stops. */
	var shown by mutableStateOf(false)
		private set
	var playing by mutableStateOf(false)
		private set

	private var silenced = false
	private var released = false
	private var soundOn = true

	init {
		setSound(false)
		player.addListener(
			object : Player.Listener {
				override fun onRenderedFirstFrame() {
					if (!silenced) shown = true
				}

				override fun onIsPlayingChanged(isPlaying: Boolean) {
					playing = isPlaying
				}

				override fun onPlaybackStateChanged(state: Int) {
					if (state == Player.STATE_ENDED) onEnded()
				}

				override fun onPlayerError(error: PlaybackException) {
					Diag.warning(DiagCategory.Player, "trailer.failed", "code" to error.errorCodeName)
					onEnded()
				}
			},
		)
	}

	/** Without an audio track nothing is decoded or sent to the speakers, so a muted trailer is not counted as sounding. */
	fun setSound(on: Boolean) {
		if (released || on == soundOn) return
		soundOn = on
		player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on).build()
	}

	/** Stops the sound now; the picture fades out before [release]. */
	fun silence() {
		if (released) return
		silenced = true
		shown = false
		player.pause()
	}

	fun release() {
		if (released) return
		released = true
		focus.abandon()
		player.release()
	}
}

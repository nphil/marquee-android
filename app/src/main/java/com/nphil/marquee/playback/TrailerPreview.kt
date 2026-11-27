package com.nphil.marquee.playback

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nphil.marquee.container
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** The client trailers are fetched over: the app's own, unless a test points them at a fake server that client cannot reach. */
internal val LocalPlaybackHttp = staticCompositionLocalOf<OkHttpClient?> { null }

/**
 * The trailer playing over a title's hero art; the caller sizes it to the hero (16:9). It starts after
 * [TrailerAutoplay.dwell] while [TrailerAutoplay.decide] allows, plays once with the tablet's sound rules, fades in
 * when its first picture is on screen, filling the hero with any black bars baked into the trailer cropped away
 * ([TrailerPicture]), and out, with its sound stopped at once, when any condition closes or the trailer nears its end.
 * It is a preview: no controls, no watch state, and only a file the tablet plays untouched ([DirectPlay]); with none,
 * it shows nothing. [visible] is true while the page is on top and the hero is on screen.
 */
@Composable
fun TrailerPreview(trailer: PlexItem, visible: Boolean, modifier: Modifier = Modifier, onPicture: ((Player?) -> Unit)? = null) {
	val context = LocalContext.current
	val container = context.container
	val coordinator = container.playback
	val prefs by rememberPlaybackPrefs()
	val signals = rememberPlaybackSignals()
	val onTop by rememberUpdatedState(visible)
	val http = LocalPlaybackHttp.current ?: container.http
	val factory = remember(http) { PlaybackFactory(context, http) }

	val conditions by remember {
		derivedStateOf {
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
			)
		}
	}
	val verdict = TrailerAutoplay.decide(conditions)
	// What holds a trailer back is otherwise invisible: nothing is logged until it starts.
	LaunchedEffect(conditions) {
		Diag.info(
			DiagCategory.Player, "trailer.conditions",
			"verdict" to verdict::class.simpleName,
			"enabled" to conditions.enabled,
			"powerSaver" to conditions.powerSaver,
			"reduceMotion" to conditions.reduceMotion,
			"metered" to conditions.metered,
			"appActive" to conditions.appActive,
			"heroVisible" to conditions.heroVisible,
			"playerOpen" to conditions.playerOpen,
		)
	}
	// It played to its end or failed: this page does not start it again.
	var finished by remember(trailer.ratingKey) { mutableStateOf(false) }
	var run by remember { mutableStateOf<TrailerRun?>(null) }
	val active = verdict != TrailerAutoplay.Verdict.Hold && !finished
	val wantsSound = active && verdict == TrailerAutoplay.Verdict.Play(muted = false)
	val current = run
	val soundOn = wantsSound && current?.focus?.held == true
	if (onPicture != null) {
		val picture = current?.takeIf { active && it.rendered && !it.stopped }?.player
		DisposableEffect(onPicture, picture) {
			onPicture(picture)
			onDispose { onPicture(null) }
		}
	}

	LaunchedEffect(active, trailer.ratingKey) {
		if (!active) {
			val ending = run ?: return@LaunchedEffect
			ending.silence()
			try {
				delay(TrailerFade.MILLIS + 50L)
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
			Diag.info(DiagCategory.Player, "trailer.unplayable", "versions" to trailer.media.size, "reason" to "noSource")
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

	if (current != null) TrailerPicture(current.player, current.rendered, current.stopped, modifier)
}

/** One trailer's player and what it reports: whether its first picture has been drawn, whether it was stopped, whether it is playing. */
@Stable
private class TrailerRun(val player: ExoPlayer, val focus: TrailerFocus, onEnded: () -> Unit) {
	/** The player has drawn its first picture; [TrailerPicture] decides when that shows. */
	var rendered by mutableStateOf(false)
		private set

	/** Stopped by [silence]: the picture fades out, and the art beneath shows again, before [release]. */
	var stopped by mutableStateOf(false)
		private set
	var playing by mutableStateOf(false)
		private set

	private var released = false
	private var soundOn = true

	init {
		setSound(false)
		player.addListener(
			object : Player.Listener {
				override fun onRenderedFirstFrame() {
					rendered = true
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
		stopped = true
		player.pause()
	}

	fun release() {
		if (released) return
		released = true
		focus.abandon()
		player.release()
	}
}

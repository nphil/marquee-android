package com.nphil.marquee.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.playback.HideSystemBars
import com.nphil.marquee.playback.LocalPlaybackHttp
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.PlayerPhase
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import java.util.Locale

/** How long the controls stay up after the last touch while the title plays, then they hide: a bright overlay does not sit on the picture. */
private const val CONTROLS_DWELL_MS = 3_000L

/**
 * The full-screen player: the picture, and over it the controls, which a tap shows and which hide 3 s after the last
 * touch while it plays. They stay up while it is paused, loading or has failed. Leaving stops the title and tells the server.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(ratingKey: String, fromStart: Boolean, onClose: () -> Unit) {
	val context = LocalContext.current
	val container = context.container
	val http = LocalPlaybackHttp.current ?: container.http
	val model = viewModel { PlayerModel(container, http, context, ratingKey, fromStart) }
	val playing = model.isPlaying
	var shown by remember { mutableStateOf(true) }
	var touches by remember { mutableIntStateOf(0) }

	// Holds the page's trailer and theme tune quiet for as long as the player is open, and the app's own sound while it plays.
	DisposableEffect(container) {
		val open = container.playback.holdPlayerOpen()
		onDispose { open.release() }
	}
	DisposableEffect(container, playing) {
		val hold = if (playing) container.playback.holdAudio() else null
		onDispose { hold?.release() }
	}
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
	LaunchedEffect(model.finished) { if (model.finished) onClose() }
	LaunchedEffect(shown, playing, touches) {
		if (!playing) {
			shown = true
		} else if (shown) {
			delay(CONTROLS_DWELL_MS)
			shown = false
		}
	}
	HideSystemBars()

	// A video's letterbox is black on every theme; the theme's canvas would frame it in light on a light one.
	Box(Modifier.fillMaxSize().background(Color.Black)) {
		val engine = model.player
		if (engine != null) {
			Video(engine, keepScreenOn = playing)
			Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { shown = !shown }) })
			if (model.isBuffering && !shown) LoadingIndicator(Modifier.align(Alignment.Center))
		}
		when (val phase = model.phase) {
			PlayerPhase.Starting -> LoadingIndicator(Modifier.align(Alignment.Center))
			is PlayerPhase.Failed -> Failure(phase.message, onRetry = model::retry, onClose = onClose, modifier = Modifier.align(Alignment.Center))
			PlayerPhase.Playing -> Unit
		}
		model.skipping?.let { total ->
			Text(
				if (total > 0) "+$total s" else "$total s",
				style = MaterialTheme.typography.headlineMedium,
				color = lucent.ink,
				modifier = Modifier
					.align(Alignment.TopCenter)
					.safeDrawingPadding()
					.padding(top = Space.s8 + Space.s6)
					.background(lucent.scrim, RoundedCornerShape(Radius.control))
					.padding(horizontal = Space.s5, vertical = Space.s3),
			)
		}
		AnimatedVisibility(visible = shown || engine == null, enter = fadeIn(), exit = fadeOut()) {
			Controls(model, onClose = onClose, onTouch = { touches++ })
		}
	}
}

@Composable
private fun Video(engine: ExoPlayer, keepScreenOn: Boolean) {
	AndroidView(
		factory = { context -> PlayerView(context).apply { useController = false } },
		// The screen stays on while the video plays, and times out when it is paused.
		update = {
			it.player = engine
			it.keepScreenOn = keepScreenOn
		},
		onRelease = { it.player = null },
		modifier = Modifier.fillMaxSize(),
	)
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
	Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		Text(message, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
			Button(onClick = onRetry) { Text("Try Again") }
			TextButton(onClick = onClose) { Text("Close") }
		}
	}
}

/** The back arrow and title above; once a player is open, skip and play in the middle and the seek bar below. */
@Composable
private fun Controls(model: PlayerModel, onClose: () -> Unit, onTouch: () -> Unit) {
	Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lucent.scrim, Color.Transparent, lucent.scrim)))) {
		TopBar(model, onClose, Modifier.align(Alignment.TopStart))
		if (model.player != null) {
			Transport(model, onTouch, Modifier.align(Alignment.Center))
			Progress(model, onTouch, Modifier.align(Alignment.BottomCenter))
		}
	}
}

@Composable
private fun TopBar(model: PlayerModel, onClose: () -> Unit, modifier: Modifier) {
	Row(
		modifier = modifier.safeDrawingPadding().padding(Space.s3),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		FilledTonalIconButton(onClick = onClose) {
			Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
		}
		if (model.title.isNotEmpty()) {
			Column(
				Modifier
					.background(lucent.scrim, RoundedCornerShape(Radius.control))
					.padding(horizontal = Space.s3, vertical = Space.s1),
			) {
				Text(model.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 1)
				model.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 1) }
			}
		}
	}
}

@Composable
private fun Transport(model: PlayerModel, onTouch: () -> Unit, modifier: Modifier) {
	Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
		SkipButton(R.drawable.ic_replay, "Back 10 seconds") {
			onTouch()
			model.skip(-SKIP_SECONDS)
		}
		Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
			if (model.isBuffering) {
				LoadingIndicator()
			} else {
				FilledIconButton(
					onClick = {
						onTouch()
						model.toggle()
					},
					modifier = Modifier.size(72.dp),
				) {
					Icon(
						painterResource(if (model.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
						contentDescription = if (model.isPlaying) "Pause" else "Play",
						modifier = Modifier.size(40.dp),
					)
				}
			}
		}
		SkipButton(R.drawable.ic_forward, "Forward 10 seconds") {
			onTouch()
			model.skip(SKIP_SECONDS)
		}
	}
}

private const val SKIP_SECONDS = 10

/** A circular arrow with the skip's length written inside it. */
@Composable
private fun SkipButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
	IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
		Box(contentAlignment = Alignment.Center) {
			Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(40.dp), tint = lucent.ink)
			Text("$SKIP_SECONDS", style = MaterialTheme.typography.labelSmall, color = lucent.ink)
		}
	}
}

/** The seek bar with the time so far and the time left. */
@Composable
private fun Progress(model: PlayerModel, onTouch: () -> Unit, modifier: Modifier) {
	val duration = model.durationMs.coerceAtLeast(1L)
	var position by remember { mutableLongStateOf(model.movieMs()) }
	var drag by remember { mutableStateOf<Float?>(null) }
	// The bar is on screen only while the controls are, so this reads the player only then.
	LaunchedEffect(model) {
		while (true) {
			if (drag == null) position = model.movieMs()
			delay(POSITION_POLL_MS)
		}
	}
	val at = drag?.let { (it * duration).toLong() } ?: position
	Column(modifier.safeDrawingPadding().padding(horizontal = Space.s4)) {
		Slider(
			value = drag ?: (position.toFloat() / duration).coerceIn(0f, 1f),
			onValueChange = {
				drag = it
				onTouch()
			},
			onValueChangeFinished = {
				drag?.let {
					position = (it * duration).toLong()
					model.seekTo(position / 1000.0)
				}
				drag = null
			},
		)
		Row(Modifier.fillMaxWidth().padding(horizontal = Space.s2), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(clock(at), style = MaterialTheme.typography.labelMedium, color = lucent.ink)
			Text("-" + clock(duration - at), style = MaterialTheme.typography.labelMedium, color = lucent.ink2)
		}
	}
}

private const val POSITION_POLL_MS = 250L

/** [ms] as `m:ss`, or `h:mm:ss` from an hour up. */
internal fun clock(ms: Long): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val hours = total / 3600
	val minutes = total % 3600 / 60
	val seconds = total % 60
	return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

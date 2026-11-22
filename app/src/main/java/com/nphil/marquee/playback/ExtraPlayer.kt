package com.nphil.marquee.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.diagnostics.findActivity
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A trailer, scene or featurette full screen, with Media3's standard controls: the stop-gap before the player. It
 * plays the file the tablet plays untouched ([DirectPlay]) and says so when there is none. Back is the caller's: it
 * removes this from its back stack, or calls [onClose] through the close button.
 */
@Composable
fun ExtraPlayer(extra: PlexItem, onClose: () -> Unit) {
	val context = LocalContext.current
	val container = context.container
	var load by remember(extra.ratingKey) { mutableStateOf<Load>(Load.Loading) }

	LaunchedEffect(extra.ratingKey) {
		val part = withContext(Dispatchers.Default) { DirectPlay.part(extra.media, DeviceDecoders.installed) }
		if (part == null) {
			Diag.info(DiagCategory.Player, "extra.unplayable", "versions" to extra.media.size)
			load = Load.Unplayable
			return@LaunchedEffect
		}
		val source = container.playbackSource(part.key)
		if (source == null) {
			load = Load.Unreachable
			return@LaunchedEffect
		}
		val player = PlaybackFactory(context, container.http).create(source.headers, PlaybackFactory.Kind.Viewer)
		player.setMediaItem(MediaItem.fromUri(source.url))
		player.prepare()
		load = Load.Ready(player)
		Diag.info(DiagCategory.Player, "extra.start", "container" to part.container)
	}
	// Holds the page's trailer and theme tune quiet for as long as the viewer is open.
	DisposableEffect(container) {
		val open = container.playback.holdPlayerOpen()
		onDispose { open.release() }
	}
	// The viewer owns its player: made once the file is found, released when it closes or shows another extra.
	DisposableEffect(extra.ratingKey) {
		onDispose { (load as? Load.Ready)?.player?.release() }
	}
	HideSystemBars()

	Box(Modifier.fillMaxSize().background(lucent.canvas)) {
		when (val shown = load) {
			Load.Loading -> LoadingIndicator(Modifier.align(Alignment.Center))
			Load.Unplayable -> Message("This video is in a format this tablet cannot play yet.")
			Load.Unreachable -> Message("The server could not be reached.")
			is Load.Ready -> Stage(shown.player)
		}
		Row(
			modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(Space.s3),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			FilledTonalIconButton(onClick = onClose) {
				Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
			}
			Text(
				extra.title,
				style = MaterialTheme.typography.titleMedium,
				color = lucent.ink,
				maxLines = 1,
				modifier = Modifier
					.background(lucent.scrim, RoundedCornerShape(Radius.control))
					.padding(horizontal = Space.s3, vertical = Space.s1),
			)
		}
	}
}

private sealed interface Load {
	data object Loading : Load
	data object Unplayable : Load
	data object Unreachable : Load
	class Ready(val player: ExoPlayer) : Load
}

@Composable
private fun Message(text: String) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		EmptyState(R.drawable.ic_movie, "Cannot play this", text)
	}
}

/** The video and Media3's controls over it. */
@Composable
private fun Stage(player: ExoPlayer) {
	val coordinator = LocalContext.current.container.playback
	var playing by remember(player) { mutableStateOf(false) }
	var failed by remember(player) { mutableStateOf(false) }

	DisposableEffect(player) {
		val listener = object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				playing = isPlaying
			}

			override fun onPlayerError(error: PlaybackException) {
				Diag.warning(DiagCategory.Player, "extra.failed", "code" to error.errorCodeName)
				failed = true
			}
		}
		player.addListener(listener)
		player.playWhenReady = true
		onDispose { player.removeListener(listener) }
	}
	// The viewer's sound is the app's own, not another app's.
	DisposableEffect(player, playing) {
		val hold = if (playing) coordinator.holdAudio() else null
		onDispose { hold?.release() }
	}
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

	if (failed) {
		Message("This video could not be played.")
	} else {
		AndroidView(
			factory = { context ->
				PlayerView(context).apply {
					this.player = player
					setShowNextButton(false)
					setShowPreviousButton(false)
				}
			},
			// The screen stays on while the video plays.
			update = { it.keepScreenOn = playing },
			onRelease = { it.player = null },
			modifier = Modifier.fillMaxSize(),
		)
	}
}

/** Full screen: the system bars are hidden while a player is open and a swipe from the edge shows them briefly. */
@Composable
internal fun HideSystemBars() {
	val view = LocalView.current
	DisposableEffect(view) {
		val window = view.context.findActivity()?.window
		val controller = window?.let { WindowCompat.getInsetsController(it, view) }
		controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		controller?.hide(WindowInsetsCompat.Type.systemBars())
		onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
	}
}

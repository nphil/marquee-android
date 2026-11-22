package com.nphil.marquee.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.nphil.marquee.MainActivity
import com.nphil.marquee.R
import com.nphil.marquee.playback.Offer
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.PlayerPhase
import kotlin.math.roundToInt

/**
 * The player's picture-in-picture window: whether this device has one, whether the app is in it, and the way in.
 * [rememberPictureInPicture] keeps the window's parameters (shape, remote actions, auto-enter) current while the
 * player screen is composed.
 */
@Stable
class PictureInPicture internal constructor(val supported: Boolean, inPictureInPicture: Boolean) {
	/** True while the window is the small floating one. The screen draws only the picture then. */
	var active by mutableStateOf(inPictureInPicture)
		internal set

	private var bounds by mutableStateOf<Rect?>(null)
	internal val surface: Rect? get() = bounds
	internal var enterNow: () -> Unit = {}

	/** The video surface's bounds in the window, for the animation into and out of the small window. */
	fun videoBounds(bounds: Rect) {
		if (this.bounds != bounds) this.bounds = bounds
	}

	/** Moves the player into the small window. Does nothing where the device has none. */
	fun enter() {
		if (supported) enterNow()
	}
}

/** What the window's parameters are built from. Equal values mean the parameters need no update. */
private data class WindowState(
	val ready: Boolean,
	val playing: Boolean,
	val finished: Boolean,
	val skipSeconds: Int,
	val offer: WindowOffer,
	val size: VideoSize,
	val surface: Rect?,
)

internal enum class WindowOffer { None, SkipIntro, NextEpisode }

internal enum class WindowAction {
	Back,
	PlayPause,
	Forward,
	SkipIntro,
	NextEpisode,
	;

	/** The broadcast name, scoped to this app so it matches nothing else. */
	fun action(context: Context): String = "${context.packageName}.pip.$name"
}

@Composable
fun rememberPictureInPicture(model: PlayerModel): PictureInPicture {
	val offer = when (model.offers.offer) {
		is Offer.SkipIntro -> WindowOffer.SkipIntro
		is Offer.UpNext -> WindowOffer.NextEpisode
		null -> WindowOffer.None
	}
	return rememberPictureInPicture(
		model.player, model.player != null && model.phase == PlayerPhase.Playing, model.isPlaying,
		model.finished, model.skipInterval, offer,
	) { action ->
		when (action) {
			WindowAction.Back -> model.skip(-model.skipInterval)
			WindowAction.Forward -> model.skip(model.skipInterval)
			WindowAction.PlayPause -> model.toggle()
			WindowAction.SkipIntro -> { model.userActed(); model.offers.skipIntro() }
			WindowAction.NextEpisode -> model.playNext()
		}
	}
}

@Composable
internal fun rememberPictureInPicture(
	player: Player?,
	ready: Boolean,
	playing: Boolean,
	finished: Boolean,
	skipSeconds: Int,
	offer: WindowOffer = WindowOffer.None,
	onAction: (WindowAction) -> Unit,
): PictureInPicture {
	val activity = LocalActivity.current as? MainActivity
	val pip = remember(activity) {
		// Only MainActivity opts into PiP in the manifest; device support alone is not enough.
		val supported = activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
		PictureInPicture(supported, supported && (activity?.isInPictureInPictureMode == true))
	}
	if (!pip.supported || activity == null) return pip

	var size by remember(player) { mutableStateOf(player?.videoSize ?: VideoSize.UNKNOWN) }
	DisposableEffect(player) {
		val listener = object : Player.Listener {
			override fun onVideoSizeChanged(videoSize: VideoSize) {
				size = videoSize
			}
		}
		player?.addListener(listener)
		onDispose { player?.removeListener(listener) }
	}
	val state = WindowState(ready, playing, finished, skipSeconds, offer, size, pip.surface)
	val current by rememberUpdatedState(state)

	LaunchedEffect(state) { activity.setPictureInPictureParams(params(activity, state)) }

	DisposableEffect(activity, pip) {
		pip.enterNow = { activity.enterPictureInPictureMode(params(activity, current)) }
		val changed = Consumer<PictureInPictureModeChangedInfo> { pip.active = it.isInPictureInPictureMode }
		activity.addOnPictureInPictureModeChangedListener(changed)
		pip.active = activity.isInPictureInPictureMode
		// Before Android 12 the app has to ask for the window itself when the person leaves while it plays.
		val leaving = Runnable {
			val now = current
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && now.ready && now.playing && !now.finished) {
				activity.enterPictureInPictureMode(params(activity, now))
			}
		}
		activity.addOnUserLeaveHintListener(leaving)
		onDispose {
			pip.enterNow = {}
			activity.removeOnPictureInPictureModeChangedListener(changed)
			activity.removeOnUserLeaveHintListener(leaving)
			// Left to itself the app would slip into the small window from the home screen.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
			}
		}
	}

	val perform by rememberUpdatedState(onAction)
	DisposableEffect(activity) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val action = WindowAction.entries.firstOrNull { it.action(context) == intent.action } ?: return
				perform(action)
				activity.setPictureInPictureParams(params(activity, current))
			}
		}
		val filter = IntentFilter().apply { WindowAction.entries.forEach { addAction(it.action(activity)) } }
		ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
		onDispose { activity.unregisterReceiver(receiver) }
	}
	return pip
}

private fun params(activity: ComponentActivity, state: WindowState): PictureInPictureParams {
	val ratio = aspectRatio(state.size)
	val builder = PictureInPictureParams.Builder()
		.setAspectRatio(Rational((ratio * RATIO_SCALE).roundToInt(), RATIO_SCALE))
		.setActions(actions(activity, state))
	state.surface?.let { builder.setSourceRectHint(videoRect(it, ratio)) }
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		builder.setAutoEnterEnabled(state.ready && state.playing && !state.finished)
		builder.setSeamlessResizeEnabled(false)
	}
	return builder.build()
}

private const val RATIO_SCALE = 10_000

/** The window takes 1:2.39 to 2.39:1 and refuses anything else; the margin keeps the rounded fraction inside. */
private fun aspectRatio(size: VideoSize): Float {
	if (size.width <= 0 || size.height <= 0) return 16f / 9f
	return (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.419f, 2.389f)
}

/** Where the picture is inside [surface]: the largest rectangle of [ratio] that fits, centred, as the player draws it. */
private fun videoRect(surface: Rect, ratio: Float): android.graphics.Rect {
	val fitsWidth = surface.width / surface.height < ratio
	val width = if (fitsWidth) surface.width else surface.height * ratio
	val height = if (fitsWidth) surface.width / ratio else surface.height
	val left = surface.left + (surface.width - width) / 2
	val top = surface.top + (surface.height - height) / 2
	return android.graphics.Rect(left.roundToInt(), top.roundToInt(), (left + width).roundToInt(), (top + height).roundToInt())
}

/** Back, play or pause and forward; while an offer is up, its button as well. What the window has no room for goes from the end. */
private fun actions(activity: ComponentActivity, state: WindowState): List<RemoteAction> {
	fun action(which: WindowAction, @DrawableRes icon: Int, title: String) = RemoteAction(
		Icon.createWithResource(activity, icon),
		title,
		title,
		PendingIntent.getBroadcast(
			activity,
			which.ordinal,
			Intent(which.action(activity)).setPackage(activity.packageName),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		),
	)
	return buildList {
		add(action(WindowAction.Back, R.drawable.ic_replay, "Back ${state.skipSeconds} seconds"))
		add(
			if (state.playing) {
				action(WindowAction.PlayPause, R.drawable.ic_pause, "Pause")
			} else {
				action(WindowAction.PlayPause, R.drawable.ic_play_arrow, "Play")
			},
		)
		add(action(WindowAction.Forward, R.drawable.ic_forward, "Forward ${state.skipSeconds} seconds"))
		when (state.offer) {
			WindowOffer.SkipIntro -> add(action(WindowAction.SkipIntro, R.drawable.ic_skip_next, "Skip Intro"))
			WindowOffer.NextEpisode -> add(action(WindowAction.NextEpisode, R.drawable.ic_skip_next, "Next Episode"))
			WindowOffer.None -> Unit
		}
	}.take(activity.maxNumPictureInPictureActions)
}

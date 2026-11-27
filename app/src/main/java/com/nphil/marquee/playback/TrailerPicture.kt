package com.nphil.marquee.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.nphil.marquee.R

/** What a trailer's node tells a test: the crop in force, and whether the picture is on screen or on its way there. */
internal data class PictureState(val bars: Bars, val shown: Boolean)

/** A function, as Compose's own scroll ranges are: the crop is read when a test asks, not when the node was last built. */
internal val PictureStateKey = SemanticsPropertyKey<() -> PictureState>("TrailerPicture")
private var SemanticsPropertyReceiver.pictureState by PictureStateKey

/** How long the crop takes to follow a change of bars; a cut to another aspect ratio is not a jump. */
private const val CROP_MILLIS = 300

/** A frame is read at this size: bars are a few rows thick even here, and a read costs the time of drawing it. */
private const val SAMPLE_WIDTH = 160
private const val SAMPLE_HEIGHT = 90

private val BarsConverter = TwoWayConverter<Bars, AnimationVector4D>(
	convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
	convertFromVector = { Bars(it.v1, it.v2, it.v3, it.v4) },
)

/**
 * The picture of [player]'s video, filling the hero it is sized to like iOS's `.resizeAspectFill`: the frame covers the
 * hero and a trailer with black bars baked in (a letterbox, a pillarbox, both) is zoomed and shifted so that its
 * active picture fills the hero and no bar shows. The bars are found by reading a few thousand pixels of the frame a
 * few times a second ([PictureReader]), so a trailer that cuts between aspect ratios is followed.
 *
 * It is drawn over the art beneath, which it meets by a cross-fade: in once [rendered] says the player has drawn a
 * first frame, the crop is settled and the frame is not dark, out ahead of the trailer's end or when it is [stopped].
 * Its colours are the art's and the video's own: the player's shutter is transparent.
 */
@Composable
internal fun TrailerPicture(player: Player, rendered: Boolean, stopped: Boolean, modifier: Modifier = Modifier) {
	val stage = remember(player) { PictureStage() }
	val drawn by rememberUpdatedState(rendered)
	val host = remember(player) { PictureHost(stage, player) { drawn } }
	DisposableEffect(host) { onDispose { host.release() } }

	val visible = stage.visible && !stopped
	val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(TrailerFade.MILLIS), label = "trailer")
	// The crop settled before the picture is let in is not seen to change, so it is set and not animated.
	var seen by remember(stage) { mutableStateOf(false) }
	LaunchedEffect(stage.visible) { if (stage.visible) seen = true }
	val crop by animateValueAsState(
		stage.bars,
		BarsConverter,
		if (seen) tween(CROP_MILLIS, easing = FastOutSlowInEasing) else snap(),
		label = "crop",
	)

	AndroidView(
		factory = { context ->
			val view = LayoutInflater.from(context).inflate(R.layout.trailer_player_view, null) as PlayerView
			host.attach(view)
			view
		},
		update = { view ->
			view.player = player
			// Android accessibility traverses PlayerView itself, not the Compose wrapper around it.
			val description = if (visible) "Trailer preview" else null
			if (view.contentDescription != description) view.contentDescription = description
			host.crop(crop)
		},
		modifier = modifier
			.graphicsLayer { this.alpha = alpha }
			.semantics {
				pictureState = { PictureState(stage.bars, visible) }
			},
		onRelease = { view ->
			host.release()
			view.player = null
		},
	)
}

/**
 * The player's view and what is done to it from outside Compose: the crop put on its video, and a timer that reads the
 * video's edges. The timer is the main thread's own clock, not Compose's: it must keep time while a test holds the
 * composition clock still, and it costs nothing between reads.
 */
internal class PictureHost(
	private val stage: PictureStage,
	private val player: Player,
	private val rendered: () -> Boolean,
) {
	private val main = Handler(Looper.getMainLooper())
	private val reader = PictureReader()
	private val frame = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
	private val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
	private var view: PlayerView? = null
	private var bars = Bars.None
	private var released = false

	private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
	private val tick = Runnable { look() }

	fun attach(view: PlayerView) {
		this.view = view
		view.addOnLayoutChangeListener(relayout)
		view.videoSurfaceView?.addOnLayoutChangeListener(relayout)
		main.postDelayed(tick, TrailerFade.EAGER_MILLIS)
	}

	/** Puts the crop on the video. The frame and the hero may change size without this being called: [relayout] follows. */
	fun crop(bars: Bars) {
		this.bars = bars
		place()
	}

	fun release() {
		if (released) return
		released = true
		main.removeCallbacks(tick)
		view?.let {
			it.removeOnLayoutChangeListener(relayout)
			it.videoSurfaceView?.removeOnLayoutChangeListener(relayout)
		}
		view = null
		frame.recycle()
	}

	/**
	 * The video is laid out to cover the hero and the player's view clips it, so scaling and shifting the video's own
	 * view crops the bars away; the player's resize mode does not need to know.
	 */
	private fun place() {
		val view = view ?: return
		val surface = view.videoSurfaceView ?: return
		if (view.width == 0 || view.height == 0 || surface.width == 0 || surface.height == 0) return
		val at = Fill.place(view.width.toFloat(), view.height.toFloat(), surface.width.toFloat(), surface.height.toFloat(), bars)
		surface.scaleX = at.scale
		surface.scaleY = at.scale
		surface.translationX = at.shiftX
		surface.translationY = at.shiftY
	}

	/**
	 * One read of the video's current frame into a small bitmap. The copy is of the texture itself, so it is the same
	 * whatever is drawn over the video and whether the video is faded out yet. It is not made until the player has
	 * drawn a first frame, and stops once the picture is leaving.
	 */
	private fun look() {
		if (released || stage.ending) return
		var next = TrailerFade.EAGER_MILLIS
		val surface = view?.videoSurfaceView as? TextureView
		if (surface != null && surface.isAvailable && rendered()) {
			// What the surface has not drawn stays transparent, which is how an empty frame is told from a black one.
			frame.eraseColor(Color.TRANSPARENT)
			surface.getBitmap(frame)
			frame.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
			next = stage.look(reader.read(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT), SystemClock.elapsedRealtime(), player.currentPosition, player.duration)
		}
		main.postDelayed(tick, next)
	}
}

package com.nphil.marquee.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * The live player's load control. Over the tuner's direct stream playback starts as soon as the renderers hold a
 * picture, instead of after the default 2.5 s of media (5 s after a stall) that a channel change would otherwise wait
 * for. Live TV arrives at the rate it plays, so that buffer is only latency. Plex's HLS keeps the defaults.
 *
 * A subclass, not a delegate: Kotlin's `by` does not forward Java default methods, and Media3 1.11's defaults for the
 * ones ExoPlayer calls throw IllegalStateException, which failed every live stream in 0.12.1.
 */
@UnstableApi
internal class LiveLoadControl : DefaultLoadControl() {
	/** Set before each prepare: the stream being loaded is the tuner's direct one. */
	@Volatile
	var direct = false

	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		if (direct) directStartsPlayback(parameters.bufferedDurationUs, parameters.rebuffering) else super.shouldStartPlayback(parameters)
}

/** First start: nothing more than the renderers' own readiness. After a stall: half a second, so one late packet does not stall it again. */
internal fun directStartsPlayback(bufferedUs: Long, rebuffering: Boolean): Boolean =
	bufferedUs >= if (rebuffering) REBUFFER_START_US else 0L

private const val REBUFFER_START_US = 500_000L

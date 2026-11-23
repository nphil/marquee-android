package com.nphil.marquee.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * The live player's load control. Over the tuner's direct stream playback starts as soon as the renderers hold a
 * picture, instead of after the default 2.5 s of media (5 s after a stall) that a channel change would otherwise wait
 * for. Live TV arrives at the rate it plays, so that buffer is only latency. Plex's HLS keeps the defaults.
 */
@UnstableApi
internal class LiveLoadControl(private val base: LoadControl = DefaultLoadControl()) : LoadControl by base {
	/** Set before each prepare: the stream being loaded is the tuner's direct one. */
	@Volatile
	var direct = false

	override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
		if (direct) directStartsPlayback(parameters.bufferedDurationUs, parameters.rebuffering) else base.shouldStartPlayback(parameters)

	// Kotlin's `by` does not forward Java default methods, and Media3 1.11's defaults for these throw
	// ("getBackBufferDurationUs not implemented"), so each one ExoPlayer calls is forwarded by hand.
	override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

	override fun onTracksSelected(
		playerId: PlayerId,
		timeline: Timeline,
		mediaPeriodId: MediaSource.MediaPeriodId,
		renderers: Array<out Renderer>,
		trackGroups: TrackGroupArray,
		trackSelections: Array<out ExoTrackSelection?>,
	) = base.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

	override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

	override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

	override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

	override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

	override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = base.shouldContinueLoading(parameters)

	override fun shouldContinuePreloading(
		playerId: PlayerId,
		timeline: Timeline,
		mediaPeriodId: MediaSource.MediaPeriodId,
		bufferedDurationUs: Long,
	): Boolean = base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

/** First start: nothing more than the renderers' own readiness. After a stall: half a second, so one late packet does not stall it again. */
internal fun directStartsPlayback(bufferedUs: Long, rebuffering: Boolean): Boolean =
	bufferedUs >= if (rebuffering) REBUFFER_START_US else 0L

private const val REBUFFER_START_US = 500_000L

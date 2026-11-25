package com.nphil.marquee.playback

import androidx.media3.common.Player

/** How long a stall may last before `player-stall` is written a second time. */
internal const val STALL_AGAIN_MS = 4500L

/** What the engine knew when playback ran dry, as the fields of one `player-stall` line (the same names as the iPhone's). */
internal class StallReading(
	val aheadMs: Long,
	val totalMs: Long,
	val isLoading: Boolean,
	val playbackState: Int,
	val playWhenReady: Boolean,
	val suppression: Int,
	val loadError: LoadFailure?,
	val estimateBps: Long,
	val droppedFrames: Int,
)

internal fun stallLineFields(reading: StallReading, again: Boolean): Array<Pair<String, Any?>> = arrayOf(
	"again" to if (again) "yes" else null,
	"ahead" to reading.aheadMs.coerceAtLeast(0),
	"buffered" to reading.totalMs,
	"loading" to if (reading.isLoading) "yes" else "no",
	"state" to stateName(reading.playbackState),
	"playWhenReady" to if (reading.playWhenReady) "yes" else "no",
	"suppressed" to suppressionName(reading.suppression),
	"loadErrType" to reading.loadError?.type,
	"loadErrStatus" to reading.loadError?.status,
	"estimateKbps" to reading.estimateBps / 1000,
	"dropped" to reading.droppedFrames,
)

private fun stateName(state: Int): String = when (state) {
	Player.STATE_IDLE -> "idle"
	Player.STATE_BUFFERING -> "buffering"
	Player.STATE_READY -> "ready"
	Player.STATE_ENDED -> "ended"
	else -> state.toString()
}

private fun suppressionName(reason: Int): String? = when (reason) {
	Player.PLAYBACK_SUPPRESSION_REASON_NONE -> null
	Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "audioFocus"
	else -> reason.toString()
}

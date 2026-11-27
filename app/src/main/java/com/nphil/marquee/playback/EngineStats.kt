package com.nphil.marquee.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import java.io.IOException

/** What the last few loads of media delivered: how many, the rate they came at in kbit/s, and how fast against playing them. */
internal data class LoadSummary(val count: Int, val kbps: Int, val realtime: Double?)

/** A failed load as the log may carry it: the exception's type and the HTTP status, never the address or its token. */
internal data class LoadFailure(val type: String, val status: Int?)

internal fun loadFailure(error: IOException): LoadFailure =
	LoadFailure(error.javaClass.simpleName, (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode)

/**
 * What a title's engine reports as it goes, for the stats overlay and the diagnostics: the decoder it picked, the frames
 * it dropped, the errors it hit and how fast media came in. Everything is read and written on the main thread.
 */
@OptIn(UnstableApi::class)
internal class EngineStats : AnalyticsListener {
	private class Load(val bytes: Long, val loadMs: Long, val mediaMs: Long)

	var videoDecoder: String? = null
		private set
	var droppedFrames = 0
		private set
	private var errors = 0
	private var lastError: String? = null

	/** The last media or playlist load that failed, kept for the stall line. */
	var lastLoadError: LoadFailure? = null
		private set
	private val recent = ArrayDeque<Load>()

	override fun onVideoDecoderInitialized(
		eventTime: AnalyticsListener.EventTime,
		decoderName: String,
		initializedTimestampMs: Long,
		initializationDurationMs: Long,
	) {
		videoDecoder = decoderName
	}

	override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
		this.droppedFrames += droppedFrames
	}

	override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
		errors++
		lastError = error.errorCodeName
	}

	override fun onLoadError(
		eventTime: AnalyticsListener.EventTime,
		loadEventInfo: LoadEventInfo,
		mediaLoadData: MediaLoadData,
		error: IOException,
		wasCanceled: Boolean,
	) {
		lastLoadError = loadFailure(error)
	}

	override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
		if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
		// A progressive file's chunks carry no media times; only a segmented stream says how much each load covered.
		val known = mediaLoadData.mediaStartTimeMs != C.TIME_UNSET && mediaLoadData.mediaEndTimeMs != C.TIME_UNSET
		val mediaMs = if (known) mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs else 0L
		record(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs, mediaMs)
		Diag.debug(
			DiagCategory.Player, "segment",
			"bytes" to loadEventInfo.bytesLoaded, "loadMs" to loadEventInfo.loadDurationMs, "mediaMs" to mediaMs,
		)
	}

	/** One load of [bytes] that took [loadMs] and covered [mediaMs] of playback, 0 when that is not known. */
	internal fun record(bytes: Long, loadMs: Long, mediaMs: Long) {
		recent.addLast(Load(bytes, loadMs, mediaMs))
		if (recent.size > RECENT_LOADS) recent.removeFirst()
	}

	/** A new stream starts counting from nothing. */
	fun reset() {
		videoDecoder = null
		droppedFrames = 0
		errors = 0
		lastError = null
		lastLoadError = null
		recent.clear()
	}

	/** What the last loads delivered, in kbit/s and as a multiple of real time (above 1 keeps ahead of playback). */
	fun loads(): LoadSummary {
		val loading = recent.sumOf { it.loadMs }
		val timed = recent.filter { it.mediaMs > 0 }
		val timedLoading = timed.sumOf { it.loadMs }
		return LoadSummary(
			count = recent.size,
			kbps = if (loading > 0) (recent.sumOf { it.bytes } * 8 / loading).toInt() else 0,
			realtime = if (timedLoading > 0) timed.sumOf { it.mediaMs }.toDouble() / timedLoading else null,
		)
	}

	/** The numbers now, with the [stalls] the model counted. */
	fun read(app: Context, engine: ExoPlayer, stalls: Int): EngineReading = EngineReading(
		videoDecoder = videoDecoder,
		displayWidth = engine.videoSize.width,
		displayHeight = engine.videoSize.height,
		networkBps = DefaultBandwidthMeter.getSingletonInstance(app).bitrateEstimate,
		streamBps = engine.videoFormat?.bitrate?.takeIf { it > 0 } ?: 0,
		droppedFrames = droppedFrames,
		skippedFrames = engine.videoDecoderCounters?.run { ensureUpdated(); skippedOutputBufferCount },
		hardwareSync = engine.isTunnelingEnabled,
		stalls = stalls,
		bufferSeconds = engine.totalBufferedDuration / 1000.0,
		errors = errors,
		lastError = lastError,
	)

	private companion object {
		const val RECENT_LOADS = 6
	}
}

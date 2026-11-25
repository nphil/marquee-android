package com.nphil.marquee.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlin.math.min

/**
 * What follows a stream that failed while it played. A lost connection, a timeout, a read error or a 5xx is a hiccup
 * of the server or the link: each load is tried again after 1, 2, 4 and 8 s ([ReconnectLoadErrorPolicy]), and a
 * stream that still fails is started again at the last played position on the same player, after 1, 2, 4 and 8 s
 * and then every 8 s, until [budgetMs] after its first failure. That makes about 30 s in all. Only a refusal that
 * stays (401, 403, 404, 410) or the budget running out ends the playback. A server that stalled for 23 s once ended
 * a film with one failed read (2026-11-25).
 */
class StreamReconnect(private val budgetMs: Long = BUDGET_MS) {
	sealed interface Step {
		/** Attempt [attempt] (from 1), after [afterMs]. */
		data class Reconnect(val attempt: Int, val afterMs: Long) : Step

		data object GiveUp : Step
	}

	var attempts = 0
		private set
	private var firstFailureMs: Long? = null
	private var landedAtMovieMs: Long? = null

	/** [nowMs] is on a monotonic clock. */
	fun failed(nowMs: Long): Step {
		val first = firstFailureMs ?: nowMs
		firstFailureMs = first
		landedAtMovieMs = null
		val left = budgetMs - (nowMs - first)
		if (left <= 0) return Step.GiveUp
		attempts++
		return Step.Reconnect(attempts, min(min(1000L shl (attempts - 1).coerceAtMost(10), MAX_DELAY_MS), left))
	}

	/** The new stream plays at [movieMs]. */
	fun landed(movieMs: Long) {
		if (attempts > 0) landedAtMovieMs = movieMs
	}

	/** The playhead is at [movieMs]; a stream that has played for a while is a new start for the next failure. */
	fun playing(movieMs: Long) {
		val landed = landedAtMovieMs ?: return
		if (movieMs - landed >= SETTLED_AFTER_MS) reset()
	}

	/** The user asked again, or another title loads. */
	fun reset() {
		attempts = 0
		firstFailureMs = null
		landedAtMovieMs = null
	}

	companion object {
		/** From the first player-level failure to the last attempt's start; the load retries come on top of it. */
		const val BUDGET_MS = 15_000L
		const val MAX_DELAY_MS = 8_000L
		const val SETTLED_AFTER_MS = 20_000L
		const val LOAD_RETRIES = 4

		/** The wait before load retry [errorCount] (from 1), or [C.TIME_UNSET] past the last. */
		fun loadRetryDelayMs(errorCount: Int): Long = if (errorCount in 1..LOAD_RETRIES) 1000L shl (errorCount - 1) else C.TIME_UNSET

		/** False for a refusal that stays; true for no status (the link) and the rest. */
		fun isRetryable(status: Int?): Boolean = status == null || status !in PERMANENT

		/** The server no longer has the conversion this session made: a new session plays on. */
		fun sessionLost(status: Int?): Boolean = status == 404 || status == 410

		/** Whether a player error is the link's or the server's, as against the file's or the device's. */
		fun isRetryable(errorCode: Int, status: Int?): Boolean = when (errorCode) {
			PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
			PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
			PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
			PlaybackException.ERROR_CODE_TIMEOUT,
			PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
			-> true
			PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> isRetryable(status)
			else -> false
		}

		private val PERMANENT = setOf(401, 403, 404, 410)

		/** The HTTP status in [error]'s cause chain, if it has one. */
		@OptIn(UnstableApi::class)
		fun httpStatus(error: Throwable): Int? {
			var cause: Throwable? = error
			while (cause != null) {
				if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
				cause = cause.cause
			}
			return null
		}
	}
}

/** The default policy, with [StreamReconnect]'s waits and without retrying a refusal that stays. */
@OptIn(UnstableApi::class)
internal class ReconnectLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
	override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
		// The default leaves out what no new try changes: a malformed file, a missing one, a cleartext refusal.
		if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
		if (!StreamReconnect.isRetryable(StreamReconnect.httpStatus(loadErrorInfo.exception))) return C.TIME_UNSET
		return StreamReconnect.loadRetryDelayMs(loadErrorInfo.errorCount)
	}

	override fun getMinimumLoadableRetryCount(dataType: Int): Int = StreamReconnect.LOAD_RETRIES
}

package com.nphil.marquee.playback

import android.os.SystemClock
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.SpeechTimingReply
import com.nphil.marquee.core.SubtitleSource
import com.nphil.marquee.core.TimedSpeechCue
import com.nphil.marquee.core.alignSpeechTiming
import com.nphil.marquee.core.prepareSpeechTiming
import com.nphil.marquee.core.stopSpeechTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** One sidecar's measured word times. The sidecar's existing clock wakes it, not the renderer. */
internal class PlayerWordTiming(private val core: MarqueeCore, private val scope: CoroutineScope) {
	private val words = mutableMapOf<Long, TimedSpeechCue>()
	private var playback: String? = null
	private var source: SubtitleSource? = null
	private var next: SpeechTimingReply? = null
	private var task: Job? = null
	private var seeked = false
	private var waitsUntil = 0L

	fun starts(at: Long, text: String): List<Double>? = words[at]?.takeIf { it.text == text }?.starts

	fun reposition() { seeked = true }

	fun prepare(file: SubtitleSource, subtitle: Int, origin: String, bytes: ByteArray) {
		stop()
		if (bytes.size > 4 * 1024 * 1024) return
		val id = UUID.randomUUID().toString()
		playback = id
		source = file
		request { core.prepareSpeechTiming(file, subtitle, id, origin, bytes) }
	}

	fun ask(due: Long) {
		val file = source ?: return
		val id = playback ?: return
		val state = next ?: return
		if (!state.active || task?.isActive == true || SystemClock.elapsedRealtime() < waitsUntil) return
		if (!seeked && (state.nextAtMs == null || due < state.nextAtMs)) return
		seeked = false
		request { core.alignSpeechTiming(file.session, id, due) }
	}

	private fun request(call: suspend () -> SpeechTimingReply) {
		task = scope.launch {
			val reply = try {
				call()
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				SpeechTimingReply(active = false)
			}
			next = reply
			if (reply.busy) waitsUntil = SystemClock.elapsedRealtime() + 2_000
			for (cue in reply.cues) words[cue.startMs] = cue
		}
	}

	fun stop() {
		task?.cancel()
		task = null
		val id = playback
		val file = source
		if (id != null && file != null) scope.launch {
			try {
				core.stopSpeechTiming(file.session, id)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// A closed player session owns no alignment state.
			}
		}
		playback = null
		source = null
		next = null
		seeked = false
		waitsUntil = 0L
		words.clear()
	}
}

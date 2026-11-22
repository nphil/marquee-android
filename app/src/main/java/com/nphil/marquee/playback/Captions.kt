package com.nphil.marquee.playback

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser

/** One visible cue and the time the file gives it. A stream cue's end may not be known yet. */
internal data class CaptionFrame(val cue: Cue, val startMs: Long, val durationMs: Long? = null)

/**
 * A separate text subtitle file's cues by time. Media3 reads the SubRip: its markup, and `{\an8}` (the line at the top
 * of the picture) as a cue placed there. Times are milliseconds into the title.
 */
@OptIn(UnstableApi::class)
internal class Captions private constructor(private val groups: List<List<CaptionFrame>>, private val timeline: CueTimeline) {
	val isEmpty: Boolean get() = groups.isEmpty()

	fun framesAt(ms: Long): List<CaptionFrame> {
		val shown = timeline.at(ms)
		return when (shown.size) {
			0 -> emptyList()
			1 -> groups[shown[0]]
			else -> shown.flatMap { groups[it] }
		}
	}

	companion object {
		fun parse(srt: ByteArray): Captions {
			val timed = ArrayList<CuesWithTiming>()
			SubripParser().parse(srt, 0, srt.size, SubtitleParser.OutputOptions.allCues()) { timed.add(it) }
			timed.sortBy { it.startTimeUs }
			val starts = LongArray(timed.size) { timed[it].startTimeUs / 1000 }
			val ends = LongArray(timed.size) { timed[it].endTimeUs / 1000 }
			return Captions(
				timed.mapIndexed { i, group -> group.cues.map { CaptionFrame(it, starts[i], ends[i] - starts[i]) } },
				CueTimeline(starts, ends),
			)
		}
	}
}

/**
 * When each cue shows, for a lookup by time that does not scan the file: a cue shows from its start up to, not
 * including, its end, so cues that follow one another never overlap. [starts] must be in order.
 */
internal class CueTimeline(private val starts: LongArray, private val ends: LongArray) {
	/** The latest end among the cues up to each index: a look back from a time can stop once nothing before can still show. */
	private val reach = LongArray(starts.size).also { reach ->
		var latest = Long.MIN_VALUE
		for (i in reach.indices) {
			latest = maxOf(latest, ends[i])
			reach[i] = latest
		}
	}

	/** The indices of the cues showing at [ms], in start order. */
	fun at(ms: Long): List<Int> {
		var low = 0
		var high = starts.size
		// The first cue that starts after ms.
		while (low < high) {
			val middle = (low + high) ushr 1
			if (starts[middle] <= ms) low = middle + 1 else high = middle
		}
		if (low == 0 || reach[low - 1] <= ms) return emptyList()
		val shown = ArrayList<Int>()
		var i = low - 1
		while (i >= 0 && reach[i] > ms) {
			if (ends[i] > ms) shown.add(i)
			i--
		}
		shown.reverse()
		return shown
	}
}

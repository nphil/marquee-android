package com.nphil.marquee.playback

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser

/**
 * A separate text subtitle file's cues by time. Media3 reads the SubRip: its markup, and `{\an8}` (the line at the top
 * of the picture) as a cue placed there. Times are milliseconds into the title.
 */
@OptIn(UnstableApi::class)
internal class Captions private constructor(private val groups: List<List<Cue>>, private val timeline: CueTimeline) {
	/** The cues on screen at [ms]. */
	fun at(ms: Long): List<Cue> {
		val shown = timeline.at(ms)
		return if (shown.isEmpty()) emptyList() else shown.flatMap { groups[it] }
	}

	companion object {
		fun parse(srt: ByteArray): Captions {
			val timed = ArrayList<CuesWithTiming>()
			SubripParser().parse(srt, 0, srt.size, SubtitleParser.OutputOptions.allCues()) { timed.add(it) }
			timed.sortBy { it.startTimeUs }
			val starts = LongArray(timed.size) { timed[it].startTimeUs / 1000 }
			val ends = LongArray(timed.size) { timed[it].endTimeUs / 1000 }
			return Captions(timed.map { it.cues }, CueTimeline(starts, ends))
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

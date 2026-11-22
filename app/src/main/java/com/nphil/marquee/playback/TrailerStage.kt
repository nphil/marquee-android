package com.nphil.marquee.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C

/** The timing of a trailer's picture crossing into and out of the hero. */
internal object TrailerFade {
	/** How long the picture takes to cross-fade in or out. */
	const val MILLIS = 400

	/** The picture shows this long after its first frame at the latest, a settled crop and a lit frame or not. */
	const val HOLD_MILLIS = 1_500L

	/** The fade out starts this long before the end, so that it is over, and no closing black card shown, when the trailer is. */
	const val LEAD_MILLIS = MILLIS + 100L

	/** In the last stretch of a trailer a dark frame is its end card: the fade out starts at once. */
	const val END_CARD_MILLIS = 4_000L

	/** The time between looks at the picture while its crop is not yet sure, and once it is. */
	const val EAGER_MILLIS = 100L
	const val STEADY_MILLIS = 250L

	/** Whether the picture should leave: the trailer is at [position] of [duration], and [dark] says its frame is. */
	fun leaving(position: Long, duration: Long, dark: Boolean): Boolean {
		if (duration == C.TIME_UNSET || duration <= 0L) return false
		val left = duration - position
		return left <= LEAD_MILLIS || (dark && left <= END_CARD_MILLIS)
	}
}

/**
 * Decides, from the looks at a trailer's frames, when its picture is let in over the hero's art and when it leaves.
 * It is let in once the player has drawn a first frame, the crop is settled and the frame is not dark, so it fades in
 * already cropped and not on black; a trailer that stays dark or unreadable is let in after [TrailerFade.HOLD_MILLIS]
 * anyway. It leaves ahead of its end. Time is passed in, so the rules can be tested without a clock.
 */
@Stable
internal class PictureStage {
	private val tracker = CropTracker()
	private var drawnAt = NEVER
	private var dark = true

	/** The crop in force. */
	var bars by mutableStateOf(Bars.None)
		private set

	/** The picture has been let in. It stays let in; whether it is on screen is [visible]. */
	var shown by mutableStateOf(false)
		private set

	/** The trailer is about to end, or has reached a dark end card: the picture leaves and does not come back. */
	var ending by mutableStateOf(false)
		private set

	/** On screen, or on its way. */
	val visible: Boolean get() = shown && !ending

	/**
	 * Takes one look at the picture at [now], the first after the player has drawn a frame, and returns how long to
	 * wait before the next. [position] and [duration] are the trailer's own, [C.TIME_UNSET] when not known.
	 */
	fun look(reading: Reading, now: Long, position: Long, duration: Long): Long {
		if (drawnAt == NEVER) drawnAt = now
		when (reading) {
			Reading.Undrawn -> Unit
			is Reading.Unclear -> dark = reading.dark
			is Reading.Clear -> {
				dark = false
				if (tracker.accept(reading.bars)) bars = tracker.bars
			}
		}
		if (!shown) {
			shown = (tracker.settled && !dark) || now - drawnAt >= TrailerFade.HOLD_MILLIS
		} else if (!ending) {
			ending = TrailerFade.leaving(position, duration, dark)
		}
		val unsure = !shown || (reading is Reading.Clear && !tracker.sure)
		return if (unsure) TrailerFade.EAGER_MILLIS else TrailerFade.STEADY_MILLIS
	}

	private companion object {
		const val NEVER = Long.MIN_VALUE
	}
}

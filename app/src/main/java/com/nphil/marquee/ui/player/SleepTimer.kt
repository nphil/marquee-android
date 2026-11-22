package com.nphil.marquee.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

private const val MINUTE_MS = 60_000L

/**
 * A timer that pauses the title after a number of minutes. It runs on the device's clock, which keeps counting while
 * the screen is off, and [deadline] and [minutes] are what survive a rotation. "Stop at the end of the episode" is the
 * model's own switch, not this timer.
 */
@Stable
internal class SleepTimer(deadline: Long = NONE, minutes: Int = 0) {
	/** When the timer runs out, on [SystemClock.elapsedRealtime]; [NONE] while it is off. */
	var deadline by mutableLongStateOf(deadline)
		private set

	/** The length it was set for, for the menu to mark; 0 while it is off. */
	var minutes by mutableIntStateOf(minutes)
		private set

	val set: Boolean get() = deadline != NONE

	fun start(minutes: Int, now: Long = SystemClock.elapsedRealtime()) {
		this.minutes = minutes
		deadline = now + minutes * MINUTE_MS
	}

	fun cancel() {
		minutes = 0
		deadline = NONE
	}

	fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long = if (set) (deadline - now).coerceAtLeast(0L) else 0L

	/** Whole minutes left, counted up: a timer with 20 seconds to go has 1. */
	fun minutesLeft(now: Long = SystemClock.elapsedRealtime()): Int = ((remainingMs(now) + MINUTE_MS - 1) / MINUTE_MS).toInt()

	companion object {
		const val NONE = -1L

		val Saver: Saver<SleepTimer, List<Long>> = Saver(
			save = { listOf(it.deadline, it.minutes.toLong()) },
			restore = { SleepTimer(it[0], it[1].toInt()) },
		)
	}
}

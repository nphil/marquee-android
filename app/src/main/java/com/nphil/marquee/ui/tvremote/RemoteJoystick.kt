package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

internal enum class DpadKey(val key: String) { Up("up"), Down("down"), Left("left"), Right("right"), Ok("ok") }

/**
 * Turns a thumb's deflection into arrow steps, paced to what the TV's focus navigation lets through: its own held-key
 * repeat waits 300 ms, then allows a step every 100 ms. Light deflection crawls at [SlowMs] per step and full
 * deflection runs at [FastMs]; the rate between follows an exponential curve so each part of the travel feels evenly
 * faster. The caller supplies the clock and gets back at most one step per call, so a stalled frame never bursts.
 */
internal class JoystickRepeater {
	/** The arrow the stick is leaning on, for lighting it; null while the stick rests. */
	var held: DpadKey? = null
		private set
	private var since = 0L
	private var last = 0L
	private var phase = 0f
	private var repeating = false

	/** [dx] and [dy] are the deflection, each in -1..1 with the stick's reach as the unit. Returns the step to send, if one is due. */
	fun update(now: Long, dx: Float, dy: Float): DpadKey? {
		val magnitude = hypot(dx, dy)
		val current = held
		if (current == null) {
			if (magnitude < DeadIn) return null
			return begin(now, dx, dy)
		}
		if (magnitude < DeadOut) {
			release()
			return null
		}
		if (switches(current, dx, dy)) return begin(now, dx, dy)
		val elapsed = now - last
		last = now
		val strength = strength(magnitude)
		if (!repeating) {
			if (now - since < (if (strength >= QuickAt) QuickHoldOffMs else HoldOffMs)) return null
			repeating = true
			phase = 0f
			return current
		}
		phase += elapsed / periodMs(strength)
		if (phase < 1f) return null
		// The wait already served carries over, up to half a period, so a stalled frame is not followed by a burst.
		phase = (phase - 1f).coerceAtMost(MaxCarry)
		return current
	}

	fun release() {
		held = null
		repeating = false
		phase = 0f
	}

	private fun begin(now: Long, dx: Float, dy: Float): DpadKey {
		val key = dominant(dx, dy)
		held = key
		since = now
		last = now
		repeating = false
		phase = 0f
		return key
	}

	/** Another axis must lead by [Lead] before the stick changes direction, so a wobble across the diagonal stays put. */
	private fun switches(current: DpadKey, dx: Float, dy: Float): Boolean {
		val candidate = dominant(dx, dy)
		if (candidate == current) return false
		val sameAxis = (current == DpadKey.Left || current == DpadKey.Right) == (candidate == DpadKey.Left || candidate == DpadKey.Right)
		if (sameAxis) return true
		return if (candidate == DpadKey.Left || candidate == DpadKey.Right) abs(dx) >= Lead * abs(dy) else abs(dy) >= Lead * abs(dx)
	}

	companion object {
		const val DeadIn = 0.22f
		const val DeadOut = 0.14f
		const val Lead = 1.25f
		const val SlowMs = 360f
		const val FastMs = 80f
		const val HoldOffMs = 300L
		const val QuickHoldOffMs = 140L
		const val QuickAt = 0.6f
		const val MaxCarry = 0.5f

		fun dominant(dx: Float, dy: Float): DpadKey = if (abs(dx) >= abs(dy)) {
			if (dx > 0f) DpadKey.Right else DpadKey.Left
		} else {
			if (dy > 0f) DpadKey.Down else DpadKey.Up
		}

		/** 0 at the edge of the dead zone, 1 at full reach. */
		fun strength(magnitude: Float): Float = ((magnitude - DeadIn) / (1f - DeadIn)).coerceIn(0f, 1f)

		fun periodMs(strength: Float): Float = SlowMs * (FastMs / SlowMs).pow(strength)
	}
}

/** The OK button's share of the D-pad disc's radius, and how far past the rim a touch still counts, so a thumb at the edge is not lost. */
internal const val OkRadius = 0.42f
internal const val DiscSlack = 1.1f

/** The key a touch at [point], in px from the disc's centre, lands on: OK in the middle, otherwise the arrow it leans toward. Null off the disc. */
internal fun dpadKeyAt(point: Offset, radius: Float): DpadKey? {
	val distance = point.getDistance()
	if (distance > radius * DiscSlack) return null
	if (distance <= radius * OkRadius) return DpadKey.Ok
	return JoystickRepeater.dominant(point.x, point.y)
}

/** The stick's deflection for a thumb [thumb] px from the centre, with [reach] px as full deflection; its length never passes 1. */
internal fun stickDeflection(thumb: Offset, reach: Float): Offset {
	if (reach <= 0f) return Offset.Zero
	val scaled = thumb / reach
	val length = scaled.getDistance()
	return if (length > 1f) scaled / length else scaled
}

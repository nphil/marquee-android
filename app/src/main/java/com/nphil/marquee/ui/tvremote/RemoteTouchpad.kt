package com.nphil.marquee.ui.tvremote

import com.nphil.marquee.tvremote.RemoteRequest
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

internal sealed interface PadEvent {
	/** Pointer motion in TV pixels. */
	data class Move(val dx: Double, val dy: Double) : PadEvent

	/** Positive scrolls right and down, as the pointer report counts it. */
	data class Scroll(val dx: Double, val dy: Double) : PadEvent
	data object Click : PadEvent
	data object Press : PadEvent
	data object Release : PadEvent
	data object Back : PadEvent

	/** Two fingers left the pad still moving: scroll on at this many TV pixels a second, then stop. */
	data class Fling(val vx: Double, val vy: Double) : PadEvent
}

/**
 * The touchpad's gesture rules, kept apart from the touch plumbing so they run on fake time. Positions are dp and
 * times seconds; the caller reports how many fingers are down and where their centre is. One finger moves the
 * pointer with a gain that rises with speed, a short tap clicks, a tap followed at once by a touch drags, two fingers
 * scroll (the content follows them), and a quick two-finger tap is Back. The same rules, constants and curve as the
 * iPhone remote's trackpad, so the pad feels the same on both.
 */
internal class TouchpadTracker(var sensitivity: Double = 1.0) {
	private enum class Phase { Idle, One, DragReady, Dragging, Two }

	private var phase = Phase.Idle
	private var startTime = 0.0
	private var lastX = 0.0
	private var lastY = 0.0
	private var lastTime = 0.0
	private var touches = 0
	private var fingers = 0
	private var travel = 0.0
	private var lastTap: Double? = null
	private var velocityX = 0.0
	private var velocityY = 0.0

	/** The fingers changed: [count] on the pad now, their centre at [x], [y]. */
	fun update(count: Int, x: Double, y: Double, time: Double): List<PadEvent> {
		when (phase) {
			Phase.Idle -> {
				if (count == 0) return emptyList()
				begin(count, x, y, time)
				val tap = lastTap
				phase = when {
					count >= 2 -> Phase.Two
					tap != null && time - tap <= DragWindow -> Phase.DragReady
					else -> Phase.One
				}
				return emptyList()
			}
			Phase.One, Phase.DragReady, Phase.Dragging -> {
				if (count == 0) return lift(time)
				if (count >= 2 && phase != Phase.Dragging) {
					phase = Phase.Two
					touches = count
					fingers = count
					anchor(x, y, time)
					return emptyList()
				}
				if (count != fingers) {
					// The centre jumps when a finger joins a drag: carry on from the new one.
					fingers = count
					anchor(x, y, time)
					return emptyList()
				}
				return moveOne(x, y, time)
			}
			Phase.Two -> {
				touches = max(touches, count)
				if (count == 0) return lift(time)
				// A finger joining or leaving moves the centre without scrolling, and one finger left alone waits for the other.
				if (count != fingers || count == 1) {
					fingers = count
					anchor(x, y, time)
					return emptyList()
				}
				return scrollTwo(x, y, time)
			}
		}
	}

	private fun begin(count: Int, x: Double, y: Double, time: Double) {
		startTime = time
		touches = count
		fingers = count
		travel = 0.0
		velocityX = 0.0
		velocityY = 0.0
		anchor(x, y, time)
	}

	private fun anchor(x: Double, y: Double, time: Double) {
		lastX = x
		lastY = y
		lastTime = time
	}

	private fun moveOne(x: Double, y: Double, time: Double): List<PadEvent> {
		val dx = x - lastX
		val dy = y - lastY
		val interval = max(time - lastTime, 0.001)
		anchor(x, y, time)
		val distance = hypot(dx, dy)
		if (distance <= 0.0) return emptyList()
		travel += distance
		val events = ArrayList<PadEvent>(2)
		if (phase == Phase.DragReady && travel > TapTravel) {
			phase = Phase.Dragging
			lastTap = null
			events += PadEvent.Press
		}
		val gain = gain(distance / interval) * sensitivity
		events += PadEvent.Move(dx * gain, dy * gain)
		return events
	}

	private fun scrollTwo(x: Double, y: Double, time: Double): List<PadEvent> {
		val dx = x - lastX
		val dy = y - lastY
		val interval = max(time - lastTime, 0.001)
		anchor(x, y, time)
		travel += hypot(dx, dy)
		// Content follows the fingers, so scrolling runs against them.
		val scrollX = -dx * ScrollGain * sensitivity
		val scrollY = -dy * ScrollGain * sensitivity
		// Smoothed over the last few samples, so the release speed is not one jittery sample.
		velocityX = velocityX * 0.5 + scrollX / interval * 0.5
		velocityY = velocityY * 0.5 + scrollY / interval * 0.5
		return if (scrollX == 0.0 && scrollY == 0.0) emptyList() else listOf(PadEvent.Scroll(scrollX, scrollY))
	}

	private fun lift(time: Double): List<PadEvent> {
		val quick = travel <= TapTravel && time - startTime <= TapTime
		val ended = phase
		phase = Phase.Idle
		return when (ended) {
			Phase.Idle -> emptyList()
			Phase.One -> if (quick) {
				lastTap = time
				listOf(PadEvent.Click)
			} else emptyList()
			Phase.DragReady -> {
				lastTap = null
				if (travel <= TapTravel) listOf(PadEvent.Click) else emptyList()
			}
			Phase.Dragging -> listOf(PadEvent.Release)
			Phase.Two -> {
				lastTap = null
				when {
					quick && touches >= 2 -> listOf(PadEvent.Back)
					time - lastTime <= RestBeforeLift && hypot(velocityX, velocityY) >= FlingSpeed -> listOf(PadEvent.Fling(velocityX, velocityY))
					else -> emptyList()
				}
			}
		}
	}

	companion object {
		const val TapTravel = 10.0
		const val TapTime = 0.25

		/** A touch this soon after a tap drags once it moves. */
		const val DragWindow = 0.3
		const val ScrollGain = 2.0

		/** Below this release speed, in TV pixels a second, scrolling just stops. */
		const val FlingSpeed = 150.0

		/** Fingers that rested this long before lifting were no longer moving, whatever their last speed. */
		const val RestBeforeLift = 0.1

		/** TV pixels per dp of finger travel: slow strokes are precise (1.2), quick ones cross the screen (up to 4). */
		fun gain(dpPerSecond: Double): Double = (1.2 + dpPerSecond / 500.0).coerceIn(1.2, 4.0)
	}
}

/** Carries a fling on as a decaying scroll. */
internal class ScrollMomentum(private var vx: Double, private var vy: Double) {
	/** The scroll for the next [seconds], or null once the speed has died away. */
	fun step(seconds: Double): Pair<Double, Double>? {
		if (hypot(vx, vy) <= StopSpeed) return null
		val decay = DecayPerSecond.pow(seconds)
		val factor = (decay - 1.0) / ln(DecayPerSecond)
		val move = vx * factor to vy * factor
		vx *= decay
		vy *= decay
		return move
	}

	companion object {
		/** Speed left after one second: 0.998 per millisecond. */
		val DecayPerSecond = 0.998.pow(1000.0)
		const val StopSpeed = 20.0
	}
}

/**
 * Carries the touchpad's gestures to the TV as pointer reports. Time is injected, so the pacing runs on a fake clock.
 * Motion is gathered and sent at most every [MinGapMs] (the bridge takes 120 samples a second); a click or a button
 * change flushes it first so they keep their order. The pointer is engaged on the first touch and released a second
 * after the last, so the TV's cursor stays where the thumb left it. After a scroll it is released at once instead:
 * the TV only clears its scrolling state when the pointer is released.
 */
internal class TouchpadSession(private val send: (RemoteRequest) -> Unit) {
	private val tracker = TouchpadTracker()

	/** Drag lock: the button is held from the first touch to the last lift, and the tracker's own clicks and drags are muted. */
	var lock = false

	/** Air mouse: the sensors move the pointer, so the finger's own motion is dropped. */
	var air = false

	var touching = false
		private set
	var engaged = false
		private set

	private var scrolled = false
	private var held = false
	private var moveX = 0.0
	private var moveY = 0.0
	private var scrollX = 0.0
	private var scrollY = 0.0
	private var lastSent = Long.MIN_VALUE / 2
	private var glide: ScrollMomentum? = null
	private var glideAt = 0L
	private var releasing = false
	private var releaseAt = 0L

	private val pending get() = moveX != 0.0 || moveY != 0.0 || scrollX != 0.0 || scrollY != 0.0

	/** True while a frame loop has work to do: fingers down, a glide, an unsent sample or a release waiting. */
	val busy: Boolean get() = touching || glide != null || releasing || pending

	/** The fingers changed: [count] on the pad, their centre at [x], [y] in dp, at [now] ms. */
	fun touch(count: Int, x: Float, y: Float, now: Long) {
		val fingers = if (lock) minOf(count, 1) else count
		if (fingers > 0 && !touching) begin()
		if (!touching) return
		for (event in tracker.update(fingers, x.toDouble(), y.toDouble(), now / 1000.0)) apply(event, now)
		if (fingers == 0) lifted(now) else flush(now, false)
	}

	/** Once per frame while [busy]: advances a glide, sends what the pacing held back, and releases a lingering pointer. */
	fun tick(now: Long) {
		val run = glide
		if (run != null) {
			val step = run.step((now - glideAt) / 1000.0)
			glideAt = now
			if (step == null) {
				glide = null
				if (!touching) release(now)
			} else {
				scrollX += step.first
				scrollY += step.second
			}
		}
		flush(now, false)
		if (releasing && !touching && now >= releaseAt) release(now)
	}

	/** The remote closes: let go of the button and the pointer so the TV is not left holding either. */
	fun stop() {
		glide = null
		touching = false
		releasing = false
		moveX = 0.0
		moveY = 0.0
		scrollX = 0.0
		scrollY = 0.0
		if (held) send(RemoteRequest(pointer = "up"))
		held = false
		if (engaged) send(RemoteRequest(pointer = "end"))
		engaged = false
		scrolled = false
	}

	private fun begin() {
		touching = true
		glide = null
		releasing = false
		if (!engaged) {
			engaged = true
			send(RemoteRequest(pointer = "begin"))
		}
		if (lock) {
			held = true
			send(RemoteRequest(pointer = "down"))
		}
	}

	private fun apply(event: PadEvent, now: Long) {
		when (event) {
			is PadEvent.Move -> if (!air) {
				moveX += event.dx
				moveY += event.dy
			}
			is PadEvent.Scroll -> {
				scrollX += event.dx
				scrollY += event.dy
			}
			PadEvent.Click -> if (!lock) {
				flush(now, true)
				send(RemoteRequest(pointer = "click"))
			}
			PadEvent.Press -> if (!lock) {
				flush(now, true)
				held = true
				send(RemoteRequest(pointer = "down"))
			}
			PadEvent.Release -> if (!lock) {
				flush(now, true)
				held = false
				send(RemoteRequest(pointer = "up"))
			}
			PadEvent.Back -> send(RemoteRequest(key = "back"))
			is PadEvent.Fling -> {
				glide = ScrollMomentum(event.vx, event.vy)
				glideAt = now
			}
		}
	}

	private fun lifted(now: Long) {
		touching = false
		flush(now, true)
		if (lock && held) {
			held = false
			send(RemoteRequest(pointer = "up"))
		}
		when {
			glide != null -> Unit
			scrolled -> release(now)
			else -> {
				releasing = true
				releaseAt = now + LingerMs
			}
		}
	}

	private fun release(now: Long) {
		flush(now, true)
		if (engaged) send(RemoteRequest(pointer = "end"))
		engaged = false
		scrolled = false
		releasing = false
		glide = null
	}

	private fun flush(now: Long, force: Boolean) {
		if (!pending || (!force && now - lastSent < MinGapMs)) return
		if (moveX != 0.0 || moveY != 0.0) {
			send(RemoteRequest(pointer = "move", x = moveX, y = moveY))
			moveX = 0.0
			moveY = 0.0
		}
		if (scrollX != 0.0 || scrollY != 0.0) {
			send(RemoteRequest(pointer = "scroll", x = scrollX, y = scrollY))
			scrolled = true
			scrollX = 0.0
			scrollY = 0.0
		}
		lastSent = now
	}

	companion object {
		/** Shortest gap between motion reports, in ms. */
		const val MinGapMs = 8L

		/** How long the pointer stays engaged after the last touch. */
		const val LingerMs = 1000L
	}
}

package com.nphil.marquee.bigscreen

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One press of the remote, as the big screen takes it. The phone's own touch surface is the only way input reaches the
 * navigator.
 */
sealed interface GlassesInput {
	data class Move(val move: FocusMove) : GlassesInput
	data object Select : GlassesInput
	data object Back : GlassesInput

	/** The thumb went down and stayed: the options on a page, the scrubber in the player. */
	data object HoldBegan : GlassesInput
	data object HoldEnded : GlassesInput

	/** While held: the thumb has slid [dx] across a [width]-dp surface. */
	data class Slide(val dx: Double, val width: Double) : GlassesInput

	/** While held in the player: tilt's fine adjustment, in seconds. */
	data class Nudge(val seconds: Double) : GlassesInput

	/** The thumb touched down; a coast stops. */
	data object PanBegan : GlassesInput

	/** The thumb is [dx], [dy] dp from where it touched down. */
	data class Pan(val dx: Double, val dy: Double) : GlassesInput

	/** The thumb lifted, moving at [vx], [vy] dp a second. */
	data class PanEnded(val vx: Double, val vy: Double) : GlassesInput
}

/**
 * The phone remote's vocabulary of touches. Each cue has its own shape (strength, sharpness, count and length) so it can
 * be told apart in the hand without looking at the phone.
 */
enum class HapticCue(val events: List<Event>) {
	/** The focus moved one item: a light, crisp tick. */
	FocusStep(listOf(tap(0.0, 0.45, 0.65))),

	/** The focus met the end of a row or list: a dull thud that lasts. */
	Edge(listOf(buzz(0.0, 0.8, 0.1, 0.1))),

	/** An item was chosen: one firm, sharp click. */
	Select(listOf(tap(0.0, 1.0, 0.9))),

	/** Went back a page: two soft knocks, the second fainter. */
	Back(listOf(tap(0.0, 0.7, 0.3), tap(0.08, 0.4, 0.3))),

	/** The playhead crossed a detent while scrubbing: the faintest bright tick. */
	ScrubDetent(listOf(tap(0.0, 0.3, 1.0))),

	/** Playback started or paused: two knocks, the second stronger. */
	PlayPause(listOf(tap(0.0, 0.55, 0.5), tap(0.1, 0.95, 0.5))),

	/** Something failed: three heavy dull knocks. */
	Error(listOf(tap(0.0, 1.0, 0.15), tap(0.12, 1.0, 0.15), tap(0.24, 1.0, 0.15))),

	/** The volume moved one step: a soft, round thump. */
	VolumeStep(listOf(tap(0.0, 0.5, 0.25))),

	/** The volume met silent or full: two firm thumps. */
	VolumeLimit(listOf(tap(0.0, 1.0, 0.25), tap(0.07, 1.0, 0.25)));

	/** [time] seconds from the start of the cue; [intensity] and [sharpness] from 0 to 1; a [duration] above zero is a held buzz. */
	data class Event(val time: Double, val intensity: Double, val sharpness: Double, val duration: Double)
}

private fun tap(time: Double, intensity: Double, sharpness: Double) = HapticCue.Event(time, intensity, sharpness, 0.0)

private fun buzz(time: Double, intensity: Double, sharpness: Double, duration: Double) = HapticCue.Event(time, intensity, sharpness, duration)

/**
 * Turns a finger's travel across the phone remote into focus steps.
 *
 * The first 12 dp pick an axis and lock it, so a diagonal thumb never moves both ways. After that every 52 dp across or
 * 70 dp down is one step, and sliding back takes steps back. A flick that took no step yet still moves one; a hard flick
 * coasts on ([coast]).
 */
class SwipeSteps {
	enum class Axis { Horizontal, Vertical }

	class Release(val step: FocusMove?, val coastMove: FocusMove?, val coastSpeed: Double)

	var axis: Axis? = null
		private set

	/** Steps taken so far along the axis, signed: right and down are positive. */
	var taken = 0
		private set

	/** The finger is [dx], [dy] dp from where it touched down; returns the steps to take now, in order. */
	fun moved(dx: Double, dy: Double): List<FocusMove> {
		if (axis == null) {
			if (max(abs(dx), abs(dy)) < LOCK_DISTANCE) return emptyList()
			axis = if (abs(dx) >= abs(dy)) Axis.Horizontal else Axis.Vertical
		}
		val horizontal = axis == Axis.Horizontal
		val travel = if (horizontal) dx else dy
		val target = (travel / if (horizontal) STEP_ACROSS else STEP_DOWN).toInt()
		val moves = ArrayList<FocusMove>()
		while (taken != target) {
			val forward = target > taken
			taken += if (forward) 1 else -1
			moves += if (horizontal) (if (forward) FocusMove.Right else FocusMove.Left) else (if (forward) FocusMove.Down else FocusMove.Up)
		}
		return moves
	}

	/** The finger lifted moving at [vx], [vy] dp a second: the one step a short flick earns, and the direction and speed a hard flick coasts on with. */
	fun released(vx: Double, vy: Double): Release {
		val horizontal = axis?.let { it == Axis.Horizontal } ?: (abs(vx) >= abs(vy))
		val speed = if (horizontal) vx else vy
		if (abs(speed) < FLICK_SPEED) return NONE
		val move = if (horizontal) (if (speed > 0) FocusMove.Right else FocusMove.Left) else (if (speed > 0) FocusMove.Down else FocusMove.Up)
		// A flick against the steps already taken is the thumb returning, not a new throw.
		if (taken != 0 && (taken > 0) != (speed > 0)) return NONE
		val step = if (taken == 0) move else null
		return if (abs(speed) >= COAST_SPEED) Release(step, move, abs(speed)) else Release(step, null, 0.0)
	}

	companion object {
		const val LOCK_DISTANCE = 12.0
		const val STEP_ACROSS = 52.0
		const val STEP_DOWN = 70.0

		/** Release speed (dp a second) above which a short flick still counts as one step. */
		const val FLICK_SPEED = 300.0

		/** Release speed above which the focus keeps moving after the finger lifts. */
		const val COAST_SPEED = 1000.0

		/** The coast stops once it has slowed below this. */
		const val STOP_SPEED = 350.0

		/** How much speed each coasting step keeps. */
		const val FRICTION = 0.85

		/** The quickest a coast steps, so every step can still be seen and felt. */
		const val SHORTEST_GAP = 0.05
		const val LONGEST_COAST = 40

		private val NONE = Release(null, null, 0.0)

		/** When each coasting step falls, in seconds after the release: quick at first, slowing as it goes. */
		fun coast(speed: Double, step: Double): List<Double> {
			if (speed < COAST_SPEED || step <= 0) return emptyList()
			val times = ArrayList<Double>()
			var current = speed
			var clock = 0.0
			while (current >= STOP_SPEED && times.size < LONGEST_COAST) {
				clock += max(step / current, SHORTEST_GAP)
				times += clock
				current *= FRICTION
			}
			return times
		}
	}
}

/**
 * Press and hold, then slide: the playhead follows the thumb, the phone's whole width standing for up to ten minutes,
 * and every ten seconds crossed is one detent the remote plays as a click.
 */
class Scrub(at: Double, duration: Double) {
	enum class Tick { None, Detent, End }

	val duration = max(duration, 0.0)
	val origin = min(max(at, 0.0), this.duration)

	/** Seconds the phone's width stands for. */
	val span = min(this.duration, WIDEST_SPAN)
	var time = origin
		private set
	private var slide = 0.0
	private var nudge = 0.0
	private var mark = (origin / DETENT).toInt()
	private var atEnd = false

	/** The thumb has slid [dx] of a [width]-dp surface since the hold began. */
	fun slid(dx: Double, width: Double): Tick {
		if (width <= 0) return Tick.None
		slide = dx / width * span
		return settle()
	}

	/** Tilt's fine adjustment, added on top of the thumb's slide. */
	fun nudge(seconds: Double): Tick {
		nudge += seconds
		// Held against an end, tilt stops pushing so tilting back answers at once.
		val reach = origin + slide + nudge
		if (reach < 0) nudge -= reach
		if (reach > duration) nudge -= reach - duration
		return settle()
	}

	private fun settle(): Tick {
		time = min(max(origin + slide + nudge, 0.0), duration)
		val ended = time <= 0 || time >= duration
		val next = Math.floor(time / DETENT).toInt()
		val crossed = next != mark
		mark = next
		val was = atEnd
		atEnd = ended
		if (ended) return if (was) Tick.None else Tick.End
		return if (crossed) Tick.Detent else Tick.None
	}

	companion object {
		const val DETENT = 10.0
		const val WIDEST_SPAN = 600.0
	}
}

/**
 * Tilting the phone while the thumb is held keeps things moving: fine scrubbing in the player. The angle counts from
 * where the phone was when the thumb went down, and the dead zone keeps a hand's tremor from moving anything. Lifting
 * the thumb stops it.
 */
class TiltDrive(
	/** Steps or seconds a second at full tilt. */
	private val topRate: Double,
) {
	/** The amount to move for [seconds] held at [angle]. */
	fun amount(angle: Double, seconds: Double): Double = level(angle) * topRate * max(seconds, 0.0)

	private var carry = 0.0

	/** Whole steps due after [seconds] held at [angle]; the remainder waits for the next reading, and a level phone drops it. */
	fun steps(angle: Double, seconds: Double): Int {
		val level = level(angle)
		if (level == 0.0) {
			carry = 0.0
			return 0
		}
		carry += level * topRate * max(seconds, 0.0)
		val whole = carry.toInt()
		carry -= whole
		return whole
	}

	companion object {
		/** About 8 degrees. */
		const val DEAD_ZONE = 0.14

		/** About 34 degrees: beyond this the rate stays at its top. */
		const val FULL_TILT = 0.6

		/** From -1 to 1: zero inside the dead zone, then rising gently so small tilts stay fine. */
		fun level(angle: Double): Double {
			val reach = abs(angle) - DEAD_ZONE
			if (reach <= 0) return 0.0
			val fraction = min(reach / (FULL_TILT - DEAD_ZONE), 1.0)
			return (if (angle < 0) -1 else 1) * fraction * fraction
		}
	}
}

/** One key of the letter board search types with on the big screen. */
sealed interface GlassesKey {
	val label: String

	data class Character(val char: Char) : GlassesKey {
		override val label: String get() = char.uppercaseChar().toString()
	}

	data object Space : GlassesKey {
		override val label = "Space"
	}

	data object Delete : GlassesKey {
		override val label = "Delete"
	}
}

/** The letter board: three rows wide enough to cross in a flick, letters then digits. */
object GlassesKeyboard {
	val rows: List<List<GlassesKey>> = listOf(
		"abcdefghijklm".map(GlassesKey::Character),
		"nopqrstuvwxyz".map(GlassesKey::Character),
		"1234567890".map(GlassesKey::Character) + listOf(GlassesKey.Space, GlassesKey.Delete),
	)

	fun type(key: GlassesKey, text: String): String = when (key) {
		is GlassesKey.Character -> text + key.char
		// Two spaces in a row, or one leading, add nothing to a search.
		GlassesKey.Space -> if (text.isEmpty() || text.endsWith(" ")) text else "$text "
		GlassesKey.Delete -> text.dropLast(1)
	}
}

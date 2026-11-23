package com.nphil.marquee.diagnostics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The frames of one scroll gesture and what they add up to, with the arithmetic and the names of the iOS app's
 * `scroll.end` line, so the two logs read side by side. Frames go into primitive arrays, so recording one allocates
 * nothing once the arrays have grown; the sorting waits for [summary]. Not thread-safe: [ScrollMonitor] uses it from
 * its worker thread only.
 */
internal class FrameLog(private val capacity: Int = CAPACITY) {
	private var intervals = DoubleArray(START)
	private var targets = DoubleArray(START)
	private var phases = ByteArray(START)

	var size = 0
		private set

	/** One frame: [interval] seconds after the one before, [target] seconds the display aimed for, [phase] the index of the gesture phase. */
	fun add(interval: Double, target: Double, phase: Int) {
		if (size == capacity) {
			// A gesture that never reports rest must not grow without end; the oldest sixth goes.
			val dropped = max(1, capacity / 6)
			intervals.copyInto(intervals, 0, dropped, size)
			targets.copyInto(targets, 0, dropped, size)
			phases.copyInto(phases, 0, dropped, size)
			size -= dropped
		}
		if (size == intervals.size) {
			val grown = min(capacity, size * 2)
			intervals = intervals.copyOf(grown)
			targets = targets.copyOf(grown)
			phases = phases.copyOf(grown)
		}
		intervals[size] = interval
		targets[size] = target
		phases[size] = phase.toByte()
		size++
	}

	fun clear() {
		size = 0
	}

	/** What the frames of [phase] (all of them for [ALL]) add up to on a panel whose top rate is [maxHz]; null when none fell in it. */
	fun summary(maxHz: Int, phase: Int = ALL): ScrollSummary? {
		var count = 0
		for (i in 0 until size) if (phase == ALL || phases[i].toInt() == phase) count++
		if (count == 0) return null
		val gaps = DoubleArray(count)
		val aims = DoubleArray(count)
		var next = 0
		for (i in 0 until size) {
			if (phase != ALL && phases[i].toInt() != phase) continue
			gaps[next] = intervals[i]
			aims[next] = targets[i]
			next++
		}
		return ScrollSummary.of(gaps, aims, maxHz)
	}

	companion object {
		const val ALL = -1

		/** Ten minutes at 120 Hz, as on iOS. */
		const val CAPACITY = 72_000
		private const val START = 1_024
	}
}

/** The numbers of a `scroll.segment` or `scroll.end` line, rounded as they are logged. */
internal class ScrollSummary(
	private val maxHz: Int,
	val seconds: Double,
	val fps: Double,
	val targetHz: Int,
	val avgMs: Double,
	val p95Ms: Double,
	val p99Ms: Double,
	val maxMs: Double,
	val hitchPct: Double,
	val severe: Int,
	val missed: Int,
	val judderPct: Double,
	val gaps: String,
) {
	/** The fields of the line, in the iOS app's order. [reason] says why a whole gesture ended; a phase's segment has none. */
	fun fields(surface: String, phase: String, reason: String?): Array<Pair<String, Any?>> = buildList<Pair<String, Any?>> {
		add("surface" to surface)
		add("phase" to phase)
		if (reason != null) add("reason" to reason)
		add("sec" to seconds)
		add("fps" to fps)
		add("target_hz" to targetHz)
		add("avg_ms" to avgMs)
		add("p95_ms" to p95Ms)
		add("p99_ms" to p99Ms)
		add("max_ms" to maxMs)
		add("hitch_pct" to hitchPct)
		add("severe" to severe)
		add("missed_$maxHz" to missed)
		add("judder_pct" to judderPct)
		add("gaps_10_14_20_30_over" to gaps)
	}.toTypedArray()

	companion object {
		/** A frame this long or longer is a stall, not a missed slot. */
		private const val SEVERE_SECONDS = 0.05

		/** Upper bounds, in ms, of the first four gap buckets: about 120 Hz, 80, 60 (or one missed 120 Hz slot), and 30. */
		private val BUCKETS = doubleArrayOf(10.5, 14.5, 20.5, 30.0)

		fun of(intervals: DoubleArray, targets: DoubleArray, maxHz: Int): ScrollSummary {
			val count = intervals.size
			val ordered = intervals.sortedArray()
			var seconds = 0.0
			for (gap in intervals) seconds += gap
			val mean = seconds / count
			var squares = 0.0
			for (gap in intervals) squares += (gap - mean) * (gap - mean)
			val spread = sqrt(squares / count)
			val slot = 1.0 / maxHz
			val target = percentile(targets.sortedArray(), 0.5)
			var hitches = 0
			var severe = 0
			var missed = 0
			val buckets = IntArray(BUCKETS.size + 1)
			for (i in 0 until count) {
				val gap = intervals[i]
				if (gap > max(targets[i], slot) * 1.5) hitches++
				if (gap >= SEVERE_SECONDS) severe++
				missed += max(0, (gap / slot).roundToInt() - 1)
				val ms = gap * 1000
				var bucket = 0
				while (bucket < BUCKETS.size && ms > BUCKETS[bucket]) bucket++
				buckets[bucket]++
			}
			return ScrollSummary(
				maxHz = maxHz,
				seconds = rounded(seconds, 2),
				fps = rounded(count / max(seconds, 0.001), 1),
				targetHz = (1 / max(target, 0.001)).roundToInt(),
				avgMs = rounded(mean * 1000, 2),
				p95Ms = rounded(percentile(ordered, 0.95) * 1000, 2),
				p99Ms = rounded(percentile(ordered, 0.99) * 1000, 2),
				maxMs = rounded(ordered.last() * 1000, 2),
				hitchPct = rounded(hitches * 100.0 / count, 1),
				severe = severe,
				missed = missed,
				judderPct = rounded(spread / max(mean, 0.001) * 100, 1),
				gaps = buckets.joinToString("/"),
			)
		}

		private fun percentile(ordered: DoubleArray, fraction: Double): Double =
			ordered[min(ordered.size - 1, ((ordered.size - 1) * fraction).roundToInt())]

		private fun rounded(value: Double, places: Int): Double {
			val scale = 10.0.pow(places)
			return (value * scale).roundToLong() / scale
		}
	}
}

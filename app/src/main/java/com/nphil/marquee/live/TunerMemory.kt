package com.nphil.marquee.live

/**
 * What the last look at one tuner found, kept for a minute as the iPhone app keeps it: a tuner that did not answer is
 * not asked again at every channel change. [K] is what the answer holds for, [T] the tuner found.
 */
internal class TunerMemory<K : Any, T : Any>(private val now: () -> Long) {
	private class Seen<K, T>(val key: K, val tuner: T?, val at: Long)

	/** The answer to [recall]: the tuner, or null when none answered. */
	class Heard<T>(val tuner: T?)

	private var seen: Seen<K, T>? = null

	/** What was found for [key] less than a minute ago, or null when that is not remembered. */
	fun recall(key: K): Heard<T>? = seen?.takeIf { it.key == key && now() - it.at < MEMORY_MS }?.let { Heard(it.tuner) }

	fun keep(key: K, tuner: T?) {
		seen = Seen(key, tuner, now())
	}

	/** A stream from [tuner] could not connect or went silent: it counts as unreachable for a minute from now. */
	fun lost(tuner: T) {
		val kept = seen ?: return
		if (kept.tuner === tuner) seen = Seen(kept.key, null, now())
	}

	private companion object {
		const val MEMORY_MS = 60_000L
	}
}

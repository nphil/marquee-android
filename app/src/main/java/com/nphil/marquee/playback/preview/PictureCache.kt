package com.nphil.marquee.playback

import java.util.TreeSet

/**
 * Pictures in memory by number. Once they pass [budget] bytes the least recently used go first; the newest always stays,
 * so one picture larger than the budget is still shown. Safe from any thread: the engines write while the screen reads.
 */
internal class PictureCache<T : Any>(private val budget: Long, private val sizeOf: (T) -> Int) {
	// Access order, so the first entry is the one used longest ago.
	private val pictures = LinkedHashMap<Int, T>(INITIAL_CAPACITY, LOAD_FACTOR, true)
	private val numbers = TreeSet<Int>()
	private var bytes = 0L

	@Synchronized
	fun contains(number: Int): Boolean = pictures.containsKey(number)

	@Synchronized
	fun put(number: Int, picture: T) {
		pictures.put(number, picture)?.let { bytes -= sizeOf(it) }
		numbers.add(number)
		bytes += sizeOf(picture)
		val eldest = pictures.entries.iterator()
		while (bytes > budget && pictures.size > 1) {
			val entry = eldest.next()
			bytes -= sizeOf(entry.value)
			numbers.remove(entry.key)
			eldest.remove()
		}
	}

	/** The picture at [number], else the nearest one within [reach] numbers; the earlier when two are as near. */
	@Synchronized
	fun nearest(number: Int, reach: Int): T? {
		pictures[number]?.let { return it }
		val before: Int? = numbers.lower(number)
		val after: Int? = numbers.higher(number)
		val behind = if (before == null) Int.MAX_VALUE else number - before
		val ahead = if (after == null) Int.MAX_VALUE else after - number
		val pick = if (behind <= ahead) before else after
		return if (pick != null && minOf(behind, ahead) <= reach) pictures[pick] else null
	}

	@Synchronized
	fun clear() {
		pictures.clear()
		numbers.clear()
		bytes = 0L
	}

	private companion object {
		const val INITIAL_CAPACITY = 16
		const val LOAD_FACTOR = 0.75f
	}
}

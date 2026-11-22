package com.nphil.marquee.playback

/**
 * The server's scene thumbnails of a part, the BIF file at `/library/parts/<id>/indexes/sd`: a 64-byte header, an index
 * of (timestamp, offset) pairs and the JPEGs those offsets point at. The iPhone app reads the same file the same way.
 * [data] stays whole: a frame is a range of it, handed to the decoder without a copy.
 */
internal class Bif private constructor(
	val data: ByteArray,
	private val times: DoubleArray,
	private val starts: IntArray,
	private val ends: IntArray,
) {
	val count: Int get() = times.size

	fun timeOf(index: Int): Double = times[index]

	fun start(index: Int): Int = starts[index]

	fun length(index: Int): Int = ends[index] - starts[index]

	/** The frame on show at [seconds]: the last one that starts at or before it, the first when [seconds] is before all. */
	fun indexAt(seconds: Double): Int {
		var low = 0
		var high = times.size - 1
		var found = 0
		while (low <= high) {
			val middle = (low + high) ushr 1
			if (times[middle] <= seconds) {
				found = middle
				low = middle + 1
			} else {
				high = middle - 1
			}
		}
		return found
	}

	companion object {
		private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)
		private const val COUNT_AT = 12
		private const val MULTIPLIER_AT = 16
		private const val INDEX_AT = 64
		private const val ENTRY = 8
		private const val LAST_STAMP = 0xFFFFFFFFL

		/** The index in [data], or null when it is not a BIF or lists no frame that lies inside it. */
		fun parse(data: ByteArray): Bif? {
			if (data.size <= INDEX_AT || MAGIC.indices.any { data[it] != MAGIC[it] }) return null
			val count = word(data, COUNT_AT)
			val multiplier = word(data, MULTIPLIER_AT)
			// The multiplier is milliseconds a tick lasts; 0 means the default, a second.
			val seconds = (if (multiplier == 0L) 1000L else multiplier) / 1000.0
			// The index cannot list more frames than it has room for, whatever the header claims.
			val room = ((data.size - INDEX_AT) / ENTRY).toLong()
			val limit = minOf(count, room).toInt()
			val times = DoubleArray(limit)
			val starts = IntArray(limit)
			val ends = IntArray(limit)
			var frames = 0
			for (index in 0 until limit) {
				val entry = INDEX_AT + index * ENTRY
				val stamp = word(data, entry)
				// The list ends with a stamp of all ones whose offset closes the last frame.
				if (stamp == LAST_STAMP) break
				val start = word(data, entry + 4)
				val end = word(data, entry + ENTRY + 4)
				if (start < 0 || end <= start || end > data.size) break
				times[frames] = stamp * seconds
				starts[frames] = start.toInt()
				ends[frames] = end.toInt()
				frames++
			}
			if (frames == 0) return null
			return Bif(data, times.copyOf(frames), starts.copyOf(frames), ends.copyOf(frames))
		}

		/** The little-endian unsigned 32-bit number at [offset]; -1 when it is not inside [data]. */
		private fun word(data: ByteArray, offset: Int): Long {
			if (offset < 0 || offset + 4 > data.size) return -1L
			var value = 0L
			for (byte in 3 downTo 0) value = (value shl 8) or (data[offset + byte].toLong() and 0xFF)
			return value
		}
	}
}

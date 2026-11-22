package com.nphil.marquee.playback

/**
 * The link probe's clock, as the iPhone keeps it: it starts at the first chunk, so the wait before the server answers
 * (it opens the file, a disk spins up) does not read as a slow link, and that chunk's own bytes are not counted. The read
 * stops [window] seconds after the first chunk, or at [maxBytes].
 */
internal class LinkRead(private val maxBytes: Int, private val window: Double) {
	private var total = 0
	private var first = 0.0
	private var last = 0.0

	/** Whether the first chunk has arrived. */
	var started = false
		private set

	/** Bytes that came after the first chunk. */
	var after = 0
		private set

	/** From the first chunk to the last. */
	val seconds: Double get() = last - first

	/** A chunk of [bytes] arrived at [now], in seconds on any steady clock; true when the read is done. */
	fun received(bytes: Int, now: Double): Boolean {
		total += bytes
		if (!started) {
			started = true
			first = now
			last = now
			return total >= maxBytes
		}
		after += bytes
		last = now
		return now - first >= window || total >= maxBytes
	}
}

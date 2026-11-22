package com.nphil.marquee.playback

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a file over HTTP as a video decoder wants it: any position, any length, in the order it likes. The last few
 * blocks stay in memory, so the decoder's habit of reading a little here and a little there costs one request.
 *
 * Android's own address reader is not used for previews because it reads ahead up to 20 MB after every jump; a frame
 * every ten seconds of a film would read most of the file. This reads a block and no more. [counter] adds up the bytes
 * the server sent. One read at a time; [abort] may come from any thread and ends the one that is waiting.
 */
internal class RangeReader(
	private val client: OkHttpClient,
	private val url: String,
	private val headers: Map<String, String>,
	private val counter: AtomicLong,
) {
	private class Block(val start: Long, val bytes: ByteArray) {
		val end: Long get() = start + bytes.size
	}

	// Least recently used first.
	private val blocks = ArrayList<Block>()
	private var length = UNKNOWN

	@Volatile
	private var live: Call? = null

	@Volatile
	private var aborted = false

	/** Reads up to [size] bytes at [position]: fewer only at the end of the file, -1 at or past it. */
	@Synchronized
	fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
		if (size == 0) return 0
		if (position < 0) return -1
		var copied = 0
		while (copied < size) {
			val at = position + copied
			val block = blockAt(at, size - copied) ?: break
			val from = (at - block.start).toInt()
			val count = minOf(size - copied, block.bytes.size - from)
			System.arraycopy(block.bytes, from, buffer, offset + copied, count)
			copied += count
		}
		return if (copied == 0) -1 else copied
	}

	/** The file's length in bytes; -1 when the server never said. */
	@Synchronized
	fun size(): Long {
		if (length == UNKNOWN) blockAt(0, 1)
		return length
	}

	/** Cancels the request waiting now and every one after it. For when the reader is let go. */
	fun abort() {
		aborted = true
		live?.cancel()
	}

	private fun blockAt(at: Long, wanted: Int): Block? {
		val kept = blocks.indexOfFirst { at >= it.start && at < it.end }
		if (kept >= 0) return blocks.removeAt(kept).also { blocks.add(it) }
		if (length != UNKNOWN && at >= length) return null
		val block = fetch(at, wanted.coerceIn(BLOCK, LARGEST)) ?: return null
		blocks.add(block)
		if (blocks.size > KEPT) blocks.removeAt(0)
		return block
	}

	/** [count] bytes from [from], fewer at the end of the file; null when [from] is at or past it. */
	private fun fetch(from: Long, count: Int): Block? {
		if (aborted) throw IOException("the reader was let go")
		var last = from + count - 1
		if (length != UNKNOWN) last = minOf(last, length - 1)
		val builder = Request.Builder().url(url)
		for ((name, value) in headers) builder.header(name, value)
		val call = client.newCall(builder.header("Range", "bytes=$from-$last").build())
		live = call
		// An abort that came before the call was in hand cancelled nothing.
		if (aborted) call.cancel()
		try {
			return call.execute().use { response -> read(response, from) }
		} finally {
			live = null
		}
	}

	private fun read(response: Response, from: Long): Block? {
		val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
		if (total != null) length = total
		when (response.code) {
			PARTIAL -> Unit
			UNSATISFIABLE -> return null
			// A 200 is the server ignoring the range: the whole file would follow.
			else -> throw IOException("the server answered ${response.code} to a range request")
		}
		val bytes = (response.body ?: throw IOException("the server sent no body")).bytes()
		counter.addAndGet(bytes.size.toLong())
		return if (bytes.isEmpty()) null else Block(from, bytes)
	}

	private companion object {
		const val UNKNOWN = -1L
		const val PARTIAL = 206
		const val UNSATISFIABLE = 416

		/** What a read that missed fetches at least: decoders read in small pieces, mostly one after another. */
		const val BLOCK = 256 * 1024

		/** The most one request asks for; a longer read is made of several. */
		const val LARGEST = 8 * 1024 * 1024
		const val KEPT = 3
	}
}

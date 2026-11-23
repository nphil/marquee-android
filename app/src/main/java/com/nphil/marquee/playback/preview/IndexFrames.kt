package com.nphil.marquee.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Previews from the server's index: the whole BIF file is read once for the title, kept in memory, and its JPEGs are
 * decoded as the scrub asks for them. The iPhone app waits ten seconds after the picture starts before it reads the
 * file, which can be several megabytes, and so does this; a scrub that comes sooner reads it at once.
 */
internal class IndexFrames<T : Any>(
	private val scope: CoroutineScope,
	private val clock: Clock,
	private val io: CoroutineDispatcher,
	private val pictures: Pictures<T>,
	private val changes: Revision,
	private val log: PreviewLog,
	private val fetch: suspend () -> ByteArray?,
) : Frames<T> {
	private val cache = PictureCache<T>(MEMORY_BYTES, pictures::size)
	private val demands = Channel<Unit>(Channel.CONFLATED)
	private val scrubbed = CompletableDeferred<Unit>()

	/** The index once it is read, or null when the server has none that can be used. */
	private val ready = CompletableDeferred<Bif?>()

	@Volatile
	private var index: Bif? = null

	@Volatile
	private var wanted = Double.NaN

	@Volatile
	private var closed = false

	override val revision: Int get() = changes.value

	/** Reads the index when the time comes. [gone] runs if the server has none that can be used. */
	fun start(gone: () -> Unit) {
		scope.launch {
			val timer = launch {
				clock.pause(START_DELAY_MS)
				scrubbed.complete(Unit)
			}
			scrubbed.await()
			timer.cancel()
			val began = clock.now()
			val bytes = attempt("fetch") { fetch() }
			val parsed = bytes?.let { attempt("parse") { withContext(io) { Bif.parse(it) } } }
			if (parsed == null || bytes == null) {
				log.event("scrub.index", "ok" to false, "ms" to clock.now() - began)
				ready.complete(null)
				gone()
				return@launch
			}
			index = parsed
			ready.complete(parsed)
			log.event("scrub.index", "ok" to true, "frames" to parsed.count, "kb" to bytes.size / 1024, "ms" to clock.now() - began)
			demands.trySend(Unit)
			serve(parsed)
		}
	}

	override fun frame(seconds: Double): T? {
		val bif = index ?: return null
		return cache.nearest(bif.indexAt(seconds), REACH)
	}

	override fun want(seconds: Double) {
		if (closed) return
		wanted = seconds
		scrubbed.complete(Unit)
		demands.trySend(Unit)
	}

	override suspend fun load(seconds: Double): T? {
		if (closed) return null
		// Whoever wants a picture now does not wait for the delay.
		scrubbed.complete(Unit)
		val bif = ready.await() ?: return null
		val number = bif.indexAt(seconds)
		cache.nearest(number, 0)?.let { return it }
		val picture = attempt("decode") {
			withContext(io) { pictures.decode(bif.data, bif.start(number), bif.length(number)) }
		} ?: return null
		cache.put(number, picture)
		changes.bump()
		return picture
	}

	override fun settle() {
		wanted = Double.NaN
	}

	override fun close() {
		closed = true
		ready.complete(null)
		index = null
		cache.clear()
	}

	/** Decodes the frame the scrub is on, newest request only: one that has been passed is not worth the time. */
	private suspend fun serve(bif: Bif) {
		while (true) {
			demands.receive()
			val seconds = wanted
			if (seconds.isNaN()) continue
			val number = bif.indexAt(seconds)
			if (cache.contains(number)) continue
			// A frame that will not decode leaves the nearest one on show.
			val picture = attempt("decode") {
				withContext(io) { pictures.decode(bif.data, bif.start(number), bif.length(number)) }
			} ?: continue
			cache.put(number, picture)
			changes.bump()
		}
	}

	/** Runs [step], and gives null if it throws: a preview that goes wrong must not reach the player. */
	private suspend fun <R : Any> attempt(name: String, step: suspend () -> R?): R? = try {
		step()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		log.event("scrub.error", "step" to "index $name", "kind" to e.javaClass.simpleName)
		null
	}

	companion object {
		/** Like the iPhone's 32 frames, in bytes: the server's frames are about 320 pixels wide. */
		const val MEMORY_BYTES = 6L * 1024 * 1024

		/** How far a stand-in may be, in frames: the one before or after, up to this many away. */
		const val REACH = 4
	}
}

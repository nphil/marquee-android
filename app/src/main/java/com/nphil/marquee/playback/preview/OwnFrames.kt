package com.nphil.marquee.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/**
 * Previews made from the file itself, for a stream the server has no index for. A frame is a keyframe near a time on a
 * grid of [SLOT_MS], so a request and the background fill land on the same frames and neither repeats the other.
 *
 * On demand: [want] is debounced, the newest request wins, and the frame is in memory and on disk when it is made.
 * Fill: once the stream has played for a while, and only while the device can spare it, one frame every ten seconds,
 * working outward from where the picture is, kept on disk for the scrub to find. It rests whenever the picture stalls,
 * the scrub is under way, the network is metered, the device is warm or the screen is off.
 */
internal class OwnFrames<T : Any>(
	private val scope: CoroutineScope,
	private val clock: Clock,
	private val source: FrameSource<T>,
	pictures: Pictures<T>,
	private val store: PictureStore<T>?,
	private val changes: Revision,
	durationMs: Long,
	private val playhead: suspend () -> Double,
	private val rebuffering: suspend () -> Boolean,
	private val conditions: FillConditions,
	private val log: PreviewLog,
) : Frames<T> {
	private val cache = PictureCache<T>(MEMORY_BYTES, pictures::size)

	// What is on disk, as listed when the engine started and as written since; the cache holds what is in memory.
	private val onDisk = ConcurrentSkipListSet<Int>()
	private val failed: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
	private val demands = Channel<Unit>(Channel.CONFLATED)

	// One frame at a time out of the source, whoever asks.
	private val turn = Mutex()
	private val lastSlot = if (durationMs > 0) ((durationMs - 1) / SLOT_MS).toInt() else NO_END
	private val strikes = AtomicInteger()
	private val made = AtomicInteger()
	private val spent = AtomicLong()
	private val fetched = AtomicLong()

	@Volatile
	private var wantedSlot = NONE

	@Volatile
	private var wantedAt = 0L

	@Volatile
	private var scrubbing = false

	@Volatile
	private var unavailable = false

	@Volatile
	private var closed = false
	private var started = false

	// Only the fill reads and writes it.
	private var restUntil = 0L

	// Held under [turn].
	private var idle: Job? = null

	override val revision: Int get() = changes.value

	/** Starts listing what is on disk, serving the scrub and, when it may, filling. */
	@Synchronized
	fun start() {
		if (started || closed) return
		started = true
		scope.launch {
			if (store != null) onDisk.addAll(store.slots().toList())
			launch { fill() }
			serve()
		}
	}

	override fun frame(seconds: Double): T? = cache.nearest(slotOf(seconds), REACH)

	override fun want(seconds: Double) {
		if (closed) return
		wantedSlot = slotOf(seconds)
		wantedAt = clock.now()
		scrubbing = true
		demands.trySend(Unit)
	}

	override fun settle() {
		scrubbing = false
		wantedSlot = NONE
	}

	override fun close() {
		if (closed) return
		closed = true
		source.close()
		cache.clear()
	}

	private fun slotOf(seconds: Double): Int = (seconds * 1000.0 / SLOT_MS + 0.5).toInt().coerceIn(0, lastSlot)

	/** Answers the scrub: what exists is shown at once, and a frame is made only for a thumb that has come to rest. */
	private suspend fun serve() {
		while (true) {
			demands.receive()
			try {
				var slot = wantedSlot
				if (slot == NONE || show(slot)) continue
				awaitRest()
				slot = wantedSlot
				if (slot == NONE || show(slot)) continue
				extract(slot, fill = false)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				stumble("serve", e)
			}
		}
	}

	/** Waits until no request has come for [DEBOUNCE_MS], but no longer than [MAX_WAIT_MS] however the thumb moves. */
	private suspend fun awaitRest() {
		val began = clock.now()
		while (true) {
			val now = clock.now()
			val still = now - wantedAt
			if (still >= DEBOUNCE_MS || now - began >= MAX_WAIT_MS) return
			clock.pause(DEBOUNCE_MS - still)
		}
	}

	/**
	 * Brings what is already made for [slot] into memory: the frame itself or, when memory has nothing near, the
	 * nearest on disk. True when it is the frame itself.
	 */
	private suspend fun show(slot: Int): Boolean {
		if (cache.contains(slot)) return true
		if (slot in onDisk && restore(slot)) return true
		if (cache.nearest(slot, REACH) == null) {
			val near = nearestOnDisk(slot)
			if (near != null) restore(near)
		}
		return false
	}

	private suspend fun restore(slot: Int): Boolean {
		val picture = store?.load(slot)
		if (picture == null) {
			// Evicted, or not a picture: forget it, so it is made again rather than asked for forever.
			onDisk.remove(slot)
			return false
		}
		cache.put(slot, picture)
		changes.bump()
		return true
	}

	private fun nearestOnDisk(slot: Int): Int? {
		val before: Int? = onDisk.floor(slot)
		val after: Int? = onDisk.ceiling(slot)
		val behind = if (before == null) Int.MAX_VALUE else slot - before
		val ahead = if (after == null) Int.MAX_VALUE else after - slot
		return if (minOf(behind, ahead) <= REACH) (if (behind <= ahead) before else after) else null
	}

	/** Takes the frame for [slot] from the file. False when it could not be had or was not needed. */
	private suspend fun extract(slot: Int, fill: Boolean): Boolean {
		if (unavailable) return false
		val taken = turn.withLock {
			// The other path may have made it while this one waited its turn.
			if (if (fill) have(slot) else cache.contains(slot)) return false
			idle?.cancel()
			val began = clock.now()
			val before = source.bytesRead
			val picture = source.frameAt(slot * SLOT_MS)
			val took = clock.now() - began
			// The file is let go when nothing has asked for a while; taking [turn] keeps that from cutting a read short.
			idle = scope.launch {
				clock.pause(IDLE_RELEASE_MS)
				turn.withLock { source.close() }
			}
			Taken(picture, took, source.bytesRead - before)
		}
		val picture = taken.picture
		if (picture == null) {
			failed.add(slot)
			if (strikes.incrementAndGet() >= MAX_STRIKES) giveUp()
			return false
		}
		strikes.set(0)
		// The scrub sees its frame before the disk is written; the fill's frames go to disk only, which changes nothing the screen shows.
		if (!fill) {
			cache.put(slot, picture)
			changes.bump()
		}
		val stored = store?.save(slot, picture) ?: 0
		if (stored > 0) {
			onDisk.add(slot)
		} else if (fill) {
			cache.put(slot, picture)
			changes.bump()
		}
		report(slot, fill, taken, stored)
		return true
	}

	private class Taken<T>(val picture: T?, val ms: Long, val bytes: Long)

	private fun have(slot: Int): Boolean = cache.contains(slot) || slot in onDisk || slot in failed

	private fun giveUp() {
		unavailable = true
		source.close()
		log.event("scrub.gaveup", "frames" to made.get())
	}

	/** Never lets one step end its loop, or the app; says what broke by its kind alone. */
	private fun stumble(step: String, e: Exception) {
		log.event("scrub.error", "step" to step, "kind" to e.javaClass.simpleName)
		if (strikes.incrementAndGet() >= MAX_STRIKES) giveUp()
	}

	private fun report(slot: Int, fill: Boolean, taken: Taken<T>, stored: Int) {
		val frames = made.incrementAndGet()
		val total = spent.addAndGet(taken.ms)
		fetched.addAndGet(taken.bytes)
		if (log.enabled) {
			log.event(
				"scrub.frame",
				"slot" to slot,
				"fill" to fill,
				"ms" to taken.ms,
				"fetchedKb" to taken.bytes / 1024,
				"storedKb" to stored / 1024,
				"frames" to frames,
				"avgMs" to total / frames,
			)
		}
	}

	/** The fill: a frame every ten seconds of the title, nearest the picture first, until there are none left to make. */
	private suspend fun fill() {
		// With no place to keep frames, or no end to stop at, there is nothing to fill.
		if (store == null || lastSlot == NO_END) return
		clock.pause(START_DELAY_MS)
		while (!unavailable) {
			var slot: Int? = null
			try {
				if (holdOff()) {
					clock.pause(POLL_MS)
					continue
				}
				slot = nextToFill(playhead())
				if (slot == null) {
					source.close()
					log.event("scrub.filled", "frames" to made.get(), "kb" to fetched.get() / 1024, "ms" to spent.get())
					return
				}
				extract(slot, fill = true)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				slot?.let { failed.add(it) }
				stumble("fill", e)
			}
			clock.pause(FILL_PAUSE_MS)
		}
	}

	/** True while the fill must wait: a scrub is under way, the picture stalls, or the device has better to do. */
	private suspend fun holdOff(): Boolean {
		if (scrubbing) return true
		if (rebuffering()) {
			restUntil = clock.now() + STALL_REST_MS
			return true
		}
		return clock.now() < restUntil || !conditions.unmetered() || !conditions.cool() || !conditions.screenOn()
	}

	/** The nearest slot on the fill's grid, by time from the picture, that has no frame yet and has not failed. */
	private fun nextToFill(seconds: Double): Int? {
		val at = if (seconds.isFinite()) (seconds * 1000.0).coerceAtLeast(0.0) else 0.0
		val lastPoint = lastSlot / FILL_STEP
		var before = minOf(floor(at / FILL_MS).toInt(), lastPoint)
		var after = before + 1
		while (before >= 0 || after <= lastPoint) {
			val behind = if (before >= 0) at - before * FILL_MS else Double.POSITIVE_INFINITY
			val ahead = if (after <= lastPoint) after * FILL_MS - at else Double.POSITIVE_INFINITY
			// Level with the picture, the one ahead of it: that is what plays next.
			val point = if (ahead <= behind) after else before
			val slot = point * FILL_STEP
			if (!have(slot)) return slot
			if (point == after) after++ else before--
		}
		return null
	}

	companion object {
		/** The grid frames lie on, in milliseconds. */
		const val SLOT_MS = 5_000L

		/** The fill takes every second slot: one frame every ten seconds. */
		const val FILL_STEP = 2
		private const val FILL_MS = (FILL_STEP * SLOT_MS).toDouble()

		/** How far a stand-in may be, in slots: thirty seconds. */
		const val REACH = 6

		/** A request is made once the thumb has rested this long, or after [MAX_WAIT_MS] however it moves. */
		const val DEBOUNCE_MS = 70L
		const val MAX_WAIT_MS = 250L

		/** How often a blocked fill looks again. */
		const val POLL_MS = 2_000L

		/** How long the fill keeps away after the picture last stalled. */
		const val STALL_REST_MS = 8_000L

		/** Between two frames of the fill, so the decoder and the network are never kept busy. */
		const val FILL_PAUSE_MS = 600L

		/** How long the file stays open with nobody asking. */
		const val IDLE_RELEASE_MS = 20_000L

		/** Frames in a row that fail before the file is given up on. */
		const val MAX_STRIKES = 3
		const val MEMORY_BYTES = 6L * 1024 * 1024
		private const val NONE = -1
		private const val NO_END = Int.MAX_VALUE
	}
}

package com.nphil.marquee.playback

import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayRoute
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.ThumbnailSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Coroutines and time moved by hand: what is launched runs when [runUntilIdle] or [advance] lets it, and a pause ends
 * when [advance] reaches it. Nothing waits for a real clock.
 */
internal class ManualTime : CoroutineDispatcher(), Clock {
	private class Sleeper(val wakes: Long, val wake: () -> Unit)

	private val ready = ArrayDeque<Runnable>()
	private val sleepers = ArrayList<Sleeper>()
	private var time = 0L

	/** True while a step of a coroutine on this dispatcher runs: what a callback sees when it asks which one called it. */
	var running = false
		private set

	override fun dispatch(context: CoroutineContext, block: Runnable) {
		ready.addLast(block)
	}

	override fun now(): Long = time

	override suspend fun pause(milliseconds: Long) = suspendCancellableCoroutine<Unit> { continuation ->
		val sleeper = Sleeper(time + milliseconds) { continuation.resume(Unit) }
		sleepers.add(sleeper)
		continuation.invokeOnCancellation { sleepers.remove(sleeper) }
	}

	/** Runs everything that is ready, and what that makes ready; time stays where it is. */
	fun runUntilIdle() {
		while (ready.isNotEmpty()) {
			val step = ready.removeFirst()
			running = true
			try {
				step.run()
			} finally {
				running = false
			}
		}
	}

	/** Moves time on, waking each pause at the moment it falls due and running what that sets going. */
	fun advance(milliseconds: Long) {
		val until = time + milliseconds
		runUntilIdle()
		while (true) {
			val next = sleepers.filter { it.wakes <= until }.minByOrNull { it.wakes } ?: break
			sleepers.remove(next)
			time = maxOf(time, next.wakes)
			next.wake()
			runUntilIdle()
		}
		time = until
	}
}

/** A picture that is a word: its size is its length, and as a JPEG it is its own bytes. */
internal object WordPictures : Pictures<String> {
	override fun size(picture: String): Int = picture.length

	override fun decode(bytes: ByteArray, offset: Int, length: Int): String = String(bytes, offset, length, Charsets.UTF_8)

	override fun encode(picture: String): ByteArray = picture.toByteArray(Charsets.UTF_8)
}

/** A file whose frame at a time is the word `frame@<ms>`; [cost] is how long each takes to make. */
internal class FakeSource(private val time: ManualTime, private val cost: Long = 0L) : FrameSource<String> {
	/** The times asked for, in the order they were asked. */
	val asked = ArrayList<Long>()
	var closes = 0
	var fails = false

	/** The most frames being made at once. */
	var mostAtOnce = 0
	private var making = 0

	override val bytesRead: Long get() = asked.size * 1_000L

	override suspend fun frameAt(timeMs: Long): String? {
		asked.add(timeMs)
		making++
		mostAtOnce = maxOf(mostAtOnce, making)
		try {
			if (cost > 0) time.pause(cost)
			return if (fails) null else "frame@$timeMs"
		} finally {
			making--
		}
	}

	override fun close() {
		closes++
	}
}

internal class FakeConditions : FillConditions {
	var unmeteredNow = true
	var coolNow = true
	var screenOnNow = true

	override fun unmetered() = unmeteredNow

	override fun cool() = coolNow

	override fun screenOn() = screenOnNow
}

internal class RecordingLog : PreviewLog {
	override val enabled = true
	val events = ArrayList<Pair<String, Map<String, Any?>>>()

	override fun event(name: String, vararg fields: Pair<String, Any?>) {
		events.add(name to fields.toMap())
	}
}

/** The device as a test sees it: time by hand, one fake file, and an index the server answers with [index]. */
internal class FakePlatform(val time: ManualTime, override val cacheRoot: File) : PreviewPlatform<String> {
	override val pictures: Pictures<String> = WordPictures
	override val clock: Clock = time
	override val engine: CoroutineDispatcher = time
	override val io: CoroutineDispatcher = time
	override val conditions = FakeConditions()
	override val log = RecordingLog()
	val source = FakeSource(time)

	/** What the server answers when the index is asked for; null is a server with none. */
	var index: ByteArray? = null
	var indexRequests = 0
	var opened = 0

	override suspend fun fetchIndex(index: ThumbnailSource): ByteArray? {
		indexRequests++
		return this.index
	}

	override fun openSource(stream: PlayStream): FrameSource<String> {
		opened++
		return source
	}
}

/** A film of part 7 that is [duration] seconds long, with the server's scene index when [index] is set. */
internal fun filmPlan(duration: Double = 100.0, index: Boolean = false) = PlayPlan(
	session = "s",
	item = PlexItem(ratingKey = "1", media = listOf(PlexMedia(parts = listOf(PlexPart(id = 7, size = 5_000L))))),
	duration = duration,
	route = PlayRoute(RouteKind.DirectPlay),
	thumbnails = if (index) ThumbnailSource("https://server.example/indexes/sd", mapOf("X-Plex-Token" to "t")) else null,
)

internal fun fileStream() = PlayStream(PlayStream.StreamKind.File, "https://server.example/film.mkv")

internal fun hlsStream() = PlayStream(PlayStream.StreamKind.Hls, "https://server.example/master.m3u8")

/**
 * A BIF whose frames are the words in [frames], stamped [stamps] ticks of [multiplier] milliseconds. The words stand
 * in for JPEGs: the tests decode with [WordPictures].
 */
internal fun bifOf(stamps: List<Long>, frames: List<String>, multiplier: Long = 1_000L): ByteArray {
	val pictures = frames.map { it.toByteArray(Charsets.UTF_8) }
	val firstFrame = 64 + (stamps.size + 1) * 8
	val buffer = ByteBuffer.allocate(firstFrame + pictures.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
	buffer.put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A))
	buffer.putInt(12, stamps.size)
	buffer.putInt(16, multiplier.toInt())
	buffer.position(64)
	var offset = firstFrame
	for (number in stamps.indices) {
		buffer.putInt(stamps[number].toInt())
		buffer.putInt(offset)
		offset += pictures[number].size
	}
	// The list ends with a stamp of all ones, whose offset closes the last frame.
	buffer.putInt(-1)
	buffer.putInt(offset)
	for (picture in pictures) buffer.put(picture)
	return buffer.array()
}

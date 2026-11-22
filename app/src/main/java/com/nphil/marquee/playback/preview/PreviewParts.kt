package com.nphil.marquee.playback

import androidx.compose.runtime.mutableIntStateOf
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.ThumbnailSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** How long the stream plays before the server's index is fetched and the background fill starts, as on the iPhone. */
internal const val START_DELAY_MS = 10_000L

/**
 * The preview engines behind [ScrubPreviews], for any picture type: the app uses Compose's ImageBitmap, a test a string.
 * [want] and [settle] are the scrub's, [frame] is read every frame.
 */
internal interface Frames<T : Any> {
	val revision: Int
	fun frame(seconds: Double): T?
	fun want(seconds: Double)
	fun settle()
	fun close()
}

/** What the engines need to know about a picture, so they can keep it in memory and on disk. */
internal interface Pictures<T : Any> {
	/** What [picture] costs in memory, in bytes. */
	fun size(picture: T): Int

	/** The picture in [length] bytes of JPEG from [offset], or null when they are not one. */
	fun decode(bytes: ByteArray, offset: Int, length: Int): T?

	/** [picture] as JPEG for the disk cache; null when it cannot be made. */
	fun encode(picture: T): ByteArray?
}

/** The file's own keyframes, one at a time. */
internal interface FrameSource<T : Any> {
	/** Bytes fetched for frames so far; 0 for a source that cannot tell. */
	val bytesRead: Long

	/** The keyframe nearest [timeMs] into the title; null when the file gives none. Suspends, never blocks the caller. */
	suspend fun frameAt(timeMs: Long): T?

	/** Lets go of the file and its thread. A later [frameAt] opens it again. */
	fun close()
}

/** Time, so a test can move it by hand. */
internal interface Clock {
	/** Milliseconds from a start nobody knows. */
	fun now(): Long

	suspend fun pause(milliseconds: Long)
}

internal object RealClock : Clock {
	private const val NANOS_PER_MILLI = 1_000_000L

	override fun now(): Long = System.nanoTime() / NANOS_PER_MILLI

	override suspend fun pause(milliseconds: Long) = delay(milliseconds)
}

/** What the background fill asks of the device before it takes a frame. */
internal interface FillConditions {
	/** The network is not metered and Data Saver is not holding the app back. */
	fun unmetered(): Boolean

	/** The device is below the moderate thermal status. */
	fun cool(): Boolean

	/** The screen is on. */
	fun screenOn(): Boolean
}

/** Where the numbers go that say what a frame cost. Names and counts only: never an address, a token or a header. */
internal interface PreviewLog {
	/** False unless something will read what [event] gets, so callers can skip building it. */
	val enabled: Boolean

	fun event(name: String, vararg fields: Pair<String, Any?>)
}

internal object NoLog : PreviewLog {
	override val enabled = false

	override fun event(name: String, vararg fields: Pair<String, Any?>) = Unit
}

/** What the engines take from the device. The app's is [AndroidPreviewPlatform]; a test fakes the pieces it watches. */
internal interface PreviewPlatform<T : Any> {
	val pictures: Pictures<T>
	val clock: Clock

	/** Where the engines' own bookkeeping runs. */
	val engine: CoroutineDispatcher

	/** Where files and JPEGs are read, written and decoded. */
	val io: CoroutineDispatcher
	val conditions: FillConditions
	val log: PreviewLog

	/** The folder frames are kept under, one folder for each part. */
	val cacheRoot: File

	/** The server's index file whole, or null when there is none to be had. Throws only to cancel. */
	suspend fun fetchIndex(index: ThumbnailSource): ByteArray?

	/** The keyframes of [stream]'s file. Nothing is opened until the first frame is asked for. */
	fun openSource(stream: PlayStream): FrameSource<T>
}

/**
 * The number [ScrubPreviews.revision] reads. It is Compose state, so what reads it recomposes when it changes. The
 * engines bump it from their own threads; the counter gives each bump a value of its own, so a write is never the one
 * already there and a reader is never left thinking nothing happened.
 */
internal class Revision {
	private val count = AtomicInteger()
	private val shown = mutableIntStateOf(0)

	val value: Int get() = shown.intValue

	fun bump() {
		shown.intValue = count.incrementAndGet()
	}
}

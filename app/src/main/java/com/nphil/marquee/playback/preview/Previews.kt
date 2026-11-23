package com.nphil.marquee.playback

import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/** The most the frames kept on disk take, across every title. */
private const val DISK_BYTES = 20L * 1024 * 1024

/**
 * The previews for [plan]'s stream, or null when it offers none. The server's index comes first when the plan has one.
 * Without one, or when it turns out to be unusable, a file is read for its own keyframes; a conversion by the server
 * (HLS) has no file to read, so it gets previews only from an index.
 *
 * Everything the previews run is a child of a scope of their own, which [Frames.close] cancels, and which ends with
 * [scope] if that goes first. It runs on the platform's engine dispatcher, whatever [scope] runs on: the screen's scope
 * is the main thread's. [playhead] and [rebuffering] are the exception: they read the player, which answers only where
 * it was made, so they run on [scope]'s thread.
 */
internal fun <T : Any> previewsFor(
	platform: PreviewPlatform<T>,
	scope: CoroutineScope,
	plan: PlayPlan,
	stream: PlayStream,
	playhead: () -> Double,
	rebuffering: () -> Boolean,
): Frames<T>? {
	val server = plan.thumbnails
	val isFile = stream.kind == PlayStream.StreamKind.File
	if (server == null && !isFile) return null
	val engines = CoroutineScope(scope.coroutineContext + platform.engine + SupervisorJob(scope.coroutineContext[Job]))
	val home = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
	val changes = Revision()
	val index = server?.let { thumbnails ->
		IndexFrames(engines, platform.clock, platform.io, platform.pictures, changes, platform.log) { platform.fetchIndex(thumbnails) }
	}
	val own = if (isFile) {
		// The size is in the name so that a replaced file, which keeps its part number, does not show the old one's frames.
		val part = plan.part()?.takeIf { it.id != 0 }
		val store = part?.let {
			PictureStore(DiskFrames(platform.cacheRoot, DISK_BYTES), "${it.id}-${it.size ?: 0}", platform.pictures, platform.io)
		}
		OwnFrames(
			engines, platform.clock, platform.openSource(stream), platform.pictures, store, changes, durationMs(plan),
			{ withContext(home) { playhead() } }, { withContext(home) { rebuffering() } }, platform.conditions, platform.log,
		)
	} else {
		null
	}
	return Previews(engines, changes, index, own)
}

private fun durationMs(plan: PlayPlan): Long =
	if (plan.duration > 0) (plan.duration * 1000).toLong() else (plan.part()?.duration ?: 0).toLong()

/**
 * The server's index while it works, then the file's own frames if it does not. A scrub under way when the index turns
 * out unusable is handed on, so the preview does not wait for the next move of the thumb.
 */
internal class Previews<T : Any>(
	private val scope: CoroutineScope,
	private val changes: Revision,
	private val index: IndexFrames<T>?,
	private val own: OwnFrames<T>?,
) : Frames<T> {
	@Volatile
	private var fallen = index == null

	@Volatile
	private var scrubbing = false

	@Volatile
	private var target = 0.0

	init {
		if (index == null) own?.start() else index.start(::fall)
	}

	override val revision: Int get() = changes.value

	override fun frame(seconds: Double): T? = active()?.frame(seconds)

	override fun want(seconds: Double) {
		target = seconds
		scrubbing = true
		active()?.want(seconds)
	}

	override suspend fun load(seconds: Double): T? = index?.takeUnless { fallen }?.load(seconds) ?: own?.load(seconds)

	override fun settle() {
		scrubbing = false
		index?.settle()
		own?.settle()
	}

	override fun close() {
		scope.cancel()
		index?.close()
		own?.close()
	}

	private fun active(): Frames<T>? = if (fallen) own else index

	private fun fall() {
		fallen = true
		// What frame() answers has changed, though nothing was made.
		changes.bump()
		own?.start()
		if (scrubbing) own?.want(target)
	}
}

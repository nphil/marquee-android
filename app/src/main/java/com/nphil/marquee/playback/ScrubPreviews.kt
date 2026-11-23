package com.nphil.marquee.playback

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayStream
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * The pictures the seek bar shows over its thumb while the person scrubs. The controls read [frame] and ask with [want];
 * the engine behind supplies the pictures from the server's scene index or, for a file, from the file itself.
 */
interface ScrubPreviews {
	/** Snapshot-state backed: changes whenever frame() could answer differently, so composables reading it recompose. */
	val revision: Int

	/** The picture for title time [seconds] now: the nearest one already available, or null. Cheap, no I/O, callable every frame. */
	fun frame(seconds: Double): ImageBitmap?

	/** The scrub is at [seconds]: ask for a better frame (debounced, newest request wins, stale ones cancelled). */
	fun want(seconds: Double)

	/**
	 * The picture of exactly [seconds]'s frame, made now: for a list that shows several at once, such as the chapters.
	 * Null when the stream cannot give it. Suspends for as long as the frame takes; a scrub's [want] does not cancel it.
	 */
	suspend fun load(seconds: Double): ImageBitmap?

	/** The scrub ended: drop pending requests. */
	fun settle()

	fun close()
}

object ScrubPreviewsFactory {
	/**
	 * The previews for [stream] of [plan], or null when the stream offers none: a conversion by the server with no scene
	 * index. [scope] bounds their life, [playhead] is where the picture is in the title, in seconds, and [rebuffering] is
	 * true while it stalls. Both are called on the thread [scope] runs on, so they may read the player.
	 */
	fun create(
		context: Context,
		scope: CoroutineScope,
		http: OkHttpClient,
		plan: PlayPlan,
		stream: PlayStream,
		playhead: () -> Double,
		rebuffering: () -> Boolean,
	): ScrubPreviews? {
		val platform = AndroidPreviewPlatform(context.applicationContext, http)
		return previewsFor(platform, scope, plan, stream, playhead, rebuffering)?.let(::Shown)
	}
}

private class Shown(private val frames: Frames<ImageBitmap>) : ScrubPreviews {
	override val revision: Int get() = frames.revision

	override fun frame(seconds: Double): ImageBitmap? = frames.frame(seconds)

	override fun want(seconds: Double) = frames.want(seconds)

	override suspend fun load(seconds: Double): ImageBitmap? = frames.load(seconds)

	override fun settle() = frames.settle()

	override fun close() = frames.close()
}

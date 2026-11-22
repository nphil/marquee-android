package com.nphil.marquee.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.ThumbnailSource
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.diagnostics.DiagRequestLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** The app's side of the previews: Compose images, the device's decoder, network, thermal state and journal. */
internal class AndroidPreviewPlatform(context: Context, private val http: OkHttpClient) : PreviewPlatform<ImageBitmap> {
	override val pictures: Pictures<ImageBitmap> = BitmapPictures
	override val clock: Clock = RealClock
	override val engine: CoroutineDispatcher = Dispatchers.Default
	override val io: CoroutineDispatcher = Dispatchers.IO
	override val conditions: FillConditions = DeviceConditions(context)
	override val log: PreviewLog = DiagPreviewLog
	override val cacheRoot = File(context.cacheDir, "scrub")

	// A frame costs a few requests and a title has hundreds of frames: the request log would drown the journal in them.
	private val ranges: OkHttpClient by lazy {
		http.newBuilder().apply { interceptors().removeAll { it is DiagRequestLog } }.build()
	}

	override suspend fun fetchIndex(index: ThumbnailSource): ByteArray? = http.download(index)

	override fun openSource(stream: PlayStream): FrameSource<ImageBitmap> = RetrieverFrameSource(ranges, stream.url, stream.headers)
}

internal object BitmapPictures : Pictures<ImageBitmap> {
	private const val BYTES_PER_PIXEL = 4
	private const val JPEG_QUALITY = 80

	override fun size(picture: ImageBitmap): Int = picture.width * picture.height * BYTES_PER_PIXEL

	override fun decode(bytes: ByteArray, offset: Int, length: Int): ImageBitmap? =
		BitmapFactory.decodeByteArray(bytes, offset, length)?.asImageBitmap()

	override fun encode(picture: ImageBitmap): ByteArray? {
		val jpeg = ByteArrayOutputStream()
		return if (picture.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)) jpeg.toByteArray() else null
	}
}

internal class DeviceConditions(context: Context) : FillConditions {
	private val connectivity = context.getSystemService(ConnectivityManager::class.java)
	private val power = context.getSystemService(PowerManager::class.java)

	override fun unmetered(): Boolean {
		val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
		// Whitelisted is fine: the person let the app past Data Saver.
		return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
			connectivity.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
	}

	override fun cool(): Boolean = power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE

	override fun screenOn(): Boolean = power.isInteractive
}

/** Lines in the journal, at the detail level that shows Debug lines and no other. */
internal object DiagPreviewLog : PreviewLog {
	override val enabled: Boolean get() = Diag.detailed

	override fun event(name: String, vararg fields: Pair<String, Any?>) {
		if (Diag.detailed) Diag.info(DiagCategory.Player, name, *fields)
	}
}

/** More than a server's index can be; only a guard against an address that is not one. */
private const val INDEX_LIMIT = 64L * 1024 * 1024

private suspend fun OkHttpClient.download(source: ThumbnailSource): ByteArray? = suspendCancellableCoroutine { continuation ->
	val call = try {
		val request = Request.Builder().url(source.url)
		for ((name, value) in source.headers) request.header(name, value)
		newCall(request.build())
	} catch (_: IllegalArgumentException) {
		continuation.resume(null)
		return@suspendCancellableCoroutine
	}
	continuation.invokeOnCancellation { call.cancel() }
	call.enqueue(object : Callback {
		override fun onFailure(call: Call, e: IOException) = continuation.resume(null)

		override fun onResponse(call: Call, response: Response) {
			val bytes = response.use { reply ->
				val body = reply.body
				if (reply.isSuccessful && body != null && body.contentLength() <= INDEX_LIMIT) {
					try {
						body.bytes()
					} catch (_: IOException) {
						null
					}
				} else {
					null
				}
			}
			continuation.resume(bytes)
		}
	})
}

/**
 * Keyframes out of the file with the device's own decoder, which can be slow and heavy: it runs on one thread of its own
 * at background priority, never more than one retriever is open, and it is let go when nothing asks. The file is read
 * through [RangeReader] rather than handed to the retriever as an address: Android's reader would fetch up to 20 MB past
 * every frame.
 */
internal class RetrieverFrameSource(
	private val client: OkHttpClient,
	private val url: String,
	private val headers: Map<String, String>,
) : FrameSource<ImageBitmap> {
	private val counter = AtomicLong()

	// Made at the first frame, and gone five seconds after the last: a thread that idles for nothing is not kept.
	private val thread = lazy {
		ThreadPoolExecutor(1, 1, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), BackgroundThreads())
			.apply { allowCoreThreadTimeOut(true) }
			.asCoroutineDispatcher()
	}

	// The thread's own: only [close] looks at the reader from elsewhere, to cancel the read it waits on.
	private var retriever: MediaMetadataRetriever? = null

	@Volatile
	private var reader: RangeReader? = null
	private var height = FALLBACK_HEIGHT

	override val bytesRead: Long get() = counter.get()

	override suspend fun frameAt(timeMs: Long): ImageBitmap? = withContext(thread.value) { grab(timeMs)?.asImageBitmap() }

	override fun close() {
		reader?.abort()
		if (thread.isInitialized()) thread.value.executor.execute { release() }
	}

	private fun grab(timeMs: Long): Bitmap? {
		val session = retriever ?: open() ?: return null
		return try {
			session.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, height)
		} catch (_: RuntimeException) {
			null
		}
	}

	private fun open(): MediaMetadataRetriever? {
		val file = if (url.startsWith("file:")) null else RangeReader(client, url, headers, counter)
		val candidate = MediaMetadataRetriever()
		return try {
			if (file == null) candidate.setDataSource(requireNotNull(Uri.parse(url).path))
			else candidate.setDataSource(RangeMedia(file))
			// A file with no picture has no previews.
			if (candidate.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
				height = heightOf(candidate)
				retriever = candidate
				reader = file
				candidate
			} else {
				discard(candidate, file)
				null
			}
		} catch (_: RuntimeException) {
			discard(candidate, file)
			null
		}
	}

	private fun discard(candidate: MediaMetadataRetriever, file: RangeReader?) {
		file?.abort()
		candidate.release()
	}

	private fun release() {
		retriever?.release()
		retriever = null
		reader = null
	}

	/** The height of a frame [WIDTH] wide as the picture is shown: a turned video is as tall as it is wide. */
	private fun heightOf(source: MediaMetadataRetriever): Int {
		fun number(key: Int) = source.extractMetadata(key)?.toIntOrNull() ?: 0
		val turned = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
		val across = number(if (turned) MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT else MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
		val down = number(if (turned) MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH else MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
		return if (across > 0 && down > 0) (WIDTH.toLong() * down / across).toInt().coerceIn(1, MAX_HEIGHT) else FALLBACK_HEIGHT
	}

	private companion object {
		const val KEEP_ALIVE_SECONDS = 5L

		/** Pixels across a preview. */
		const val WIDTH = 240
		const val FALLBACK_HEIGHT = WIDTH * 9 / 16
		const val MAX_HEIGHT = WIDTH * 4
	}
}

/** The decoder's view of a [RangeReader]. */
private class RangeMedia(private val reader: RangeReader) : MediaDataSource() {
	override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)

	override fun getSize(): Long = reader.size()

	override fun close() = reader.abort()
}

private class BackgroundThreads : ThreadFactory {
	override fun newThread(task: Runnable): Thread = Thread(
		{
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
			task.run()
		},
		"scrub-previews",
	).apply { isDaemon = true }
}

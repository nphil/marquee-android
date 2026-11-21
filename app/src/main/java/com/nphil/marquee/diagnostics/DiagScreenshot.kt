package com.nphil.marquee.diagnostics

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** The screenshot a report carries. */
internal object DiagScreenshot {
	private const val MAX_SIDE = 1400
	private const val MAX_BYTES = 2_000_000
	private val QUALITIES = intArrayOf(70, 50, 30)
	private const val LAST_RESORT = 20

	/** At most 1400 pixels on the long side, which keeps the interface readable at a fraction of the size. */
	fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
		val factor = minOf(1.0, MAX_SIDE.toDouble() / maxOf(width, height, 1))
		return (width * factor).roundToInt().coerceAtLeast(1) to (height * factor).roundToInt().coerceAtLeast(1)
	}

	/**
	 * The activity's window as it looks, without the floating button, which is a window of its own. Null where
	 * the system cannot copy the window; the report then goes without a screenshot.
	 */
	suspend fun capture(activity: Activity): Bitmap? {
		val window = activity.window
		val view = window.decorView
		if (view.width == 0 || view.height == 0) return null
		val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
		return suspendCancellableCoroutine { continuation ->
			try {
				PixelCopy.request(window, bitmap, { result ->
					if (result == PixelCopy.SUCCESS) {
						continuation.resume(bitmap)
					} else {
						bitmap.recycle()
						continuation.resume(null)
					}
				}, Handler(Looper.getMainLooper()))
			} catch (_: IllegalArgumentException) {
				bitmap.recycle()
				continuation.resume(null)
			}
		}
	}

	/** A JPEG of at most about 2 MB: the quality steps down until it fits. */
	fun jpeg(source: Bitmap): ByteArray {
		val (width, height) = scaledSize(source.width, source.height)
		val scaled = if (width == source.width && height == source.height) source else Bitmap.createScaledBitmap(source, width, height, true)
		try {
			for (quality in QUALITIES) {
				val data = compress(scaled, quality)
				if (data.size <= MAX_BYTES) return data
			}
			return compress(scaled, LAST_RESORT)
		} finally {
			if (scaled !== source) scaled.recycle()
		}
	}

	private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
		ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

package com.nphil.marquee.testing

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

private const val Tag = "MarqueeReview"
private const val LoadMillis = 20_000L

/** Long enough for rows that scroll into view to have asked for their pictures. */
private const val SettleMillis = 300L

/**
 * The app's artwork over a fake server. The app's own loader fetches over its real network, where the fake server's
 * address leads nowhere, so a screenshot would show empty posters. This loader asks [client] instead, and counts the
 * pictures still on their way so a test can wait for them.
 */
class FakeArt(private val context: Context, private val client: OkHttpClient) {
	private val asked = AtomicInteger()
	private val pending = AtomicInteger()

	@OptIn(DelicateCoilApi::class)
	fun install() {
		val loader = ImageLoader.Builder(context)
			.components {
				add(OkHttpNetworkFetcherFactory(callFactory = { client }))
				// A fade still under way when the frame is captured would leave posters half drawn.
				add(Interceptor { chain -> chain.withRequest(chain.request.newBuilder().crossfade(false).build()).proceed() })
			}
			.eventListener(Counting())
			.build()
		SingletonImageLoader.setUnsafe(loader)
	}

	/** Gives the app its own loader back. */
	@OptIn(DelicateCoilApi::class)
	fun restore() = SingletonImageLoader.reset()

	/**
	 * Waits until some picture has been asked for and every one asked for has arrived, then until another moment
	 * has passed with nothing new asked for: a lazy grid asks for its rows' posters as they come into view.
	 */
	fun awaitSettled(rule: ComposeTestRule) {
		rule.waitUntil("a picture asked for and none still on its way", LoadMillis) { asked.get() > 0 && pending.get() == 0 }
		do {
			val before = asked.get()
			Thread.sleep(SettleMillis)
			rule.waitForIdle()
			rule.waitUntil("no picture still on its way", LoadMillis) { pending.get() == 0 }
		} while (asked.get() != before)
	}

	private inner class Counting : EventListener() {
		override fun onStart(request: ImageRequest) {
			asked.incrementAndGet()
			pending.incrementAndGet()
		}

		override fun onSuccess(request: ImageRequest, result: SuccessResult) {
			pending.decrementAndGet()
		}

		override fun onError(request: ImageRequest, result: ErrorResult) {
			pending.decrementAndGet()
			Log.w(Tag, "artwork did not load: ${request.data}", result.throwable)
		}

		override fun onCancel(request: ImageRequest) {
			pending.decrementAndGet()
		}
	}
}

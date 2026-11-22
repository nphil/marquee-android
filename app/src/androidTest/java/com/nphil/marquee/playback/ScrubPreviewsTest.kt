package com.nphil.marquee.playback

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayRoute
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.RouteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The previews a file makes of itself, with the device's own decoder: [Clips.Flat], served over HTTP with ranges as a
 * Plex server serves a file, is asked for a frame as a scrub would.
 */
@RunWith(AndroidJUnit4::class)
class ScrubPreviewsTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val server = MockWebServer()
	private val frames = context.cacheDir.resolve("scrub")
	private lateinit var clip: ByteArray
	private var previews: ScrubPreviews? = null

	@Before
	fun serveTheClip() {
		// A frame kept by an earlier run would be shown without the decoder being asked.
		frames.deleteRecursively()
		clip = InstrumentationRegistry.getInstrumentation().context.assets.open(Clips.Flat.file).use { it.readBytes() }
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				val range = request.getHeader("Range")?.removePrefix("bytes=")?.split("-") ?: return MockResponse().setResponseCode(400)
				val first = range[0].toInt()
				if (first >= clip.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${clip.size}")
				val last = minOf(range[1].toInt(), clip.size - 1)
				return MockResponse()
					.setResponseCode(206)
					.setHeader("Content-Range", "bytes $first-$last/${clip.size}")
					.setBody(Buffer().write(clip, first, last - first + 1))
			}
		}
		server.start()
	}

	@After
	fun cleanUp() {
		previews?.close()
		scope.cancel()
		server.shutdown()
		frames.deleteRecursively()
	}

	@Test
	fun aFileMakesAFrameOfItselfForTheTimeScrubbedTo() {
		val plan = PlayPlan(
			session = "s",
			item = PlexItem(ratingKey = "1", media = listOf(PlexMedia(parts = listOf(PlexPart(id = 7, size = clip.size.toLong()))))),
			duration = 4.0,
			route = PlayRoute(RouteKind.DirectPlay),
		)
		val stream = PlayStream(PlayStream.StreamKind.File, server.url("/film.mp4").toString())
		val shown = ScrubPreviewsFactory.create(context, scope, OkHttpClient(), plan, stream, { 0.0 }, { false })
		assertNotNull(shown)
		previews = shown
		val before = shown!!.revision

		shown.want(2.0)
		val deadline = SystemClock.elapsedRealtime() + 8_000
		while (shown.revision == before && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)

		val frame = shown.frame(2.0)
		assertNotNull(frame)
		assertTrue(frame!!.width <= 240)
	}
}

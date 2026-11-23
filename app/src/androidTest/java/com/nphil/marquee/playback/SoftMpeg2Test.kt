package com.nphil.marquee.playback

import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.ffmpeg.FfmpegVideoRenderer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FFmpeg's MPEG-2 decoder on the emulator: assets/mpeg2/interlaced.ts played to its end by [FfmpegVideoRenderer] alone
 * onto a TextureView, the way a trailer is shown, with the renderer told the platform has no MPEG-2 decoder, as it is
 * on the tablets it is for. The sample is 45 frames of two fields each, so the deinterlacer must give about 90
 * pictures, and the view must hold a picture, not black.
 *
 * Made with ffmpeg 5.1 and its mpeg2video encoder: 1.5 s of testsrc2, 352x240, top field first, 29.97 frames a second:
 *
 *     ffmpeg -f lavfi -i "testsrc2=s=352x240:r=60000/1001:d=1.5,format=yuv420p,tinterlace=interleave_top" \
 *         -c:v mpeg2video -b:v 450k -maxrate 450k -bufsize 450k -g 12 -bf 2 -flags +ilme+ildct -top 1 -an \
 *         -f mpegts interlaced.ts
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SoftMpeg2Test {
	@get:Rule
	val activity = ActivityScenarioRule(ComponentActivity::class.java)

	@Test
	fun interlacedMpeg2PlaysThroughFfmpegAsAPicturePerField() {
		assertTrue("this build has no FFmpeg libraries", FfmpegVideoRenderer.isAvailable())
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val target = instrumentation.targetContext
		val file = File(target.cacheDir, "interlaced.ts")
		instrumentation.context.assets.open("mpeg2/interlaced.ts").use { input -> file.outputStream().use { input.copyTo(it) } }

		val counted = Counted()
		val ended = CountDownLatch(1)
		val failure = AtomicReference<PlaybackException>()
		lateinit var player: ExoPlayer
		lateinit var view: TextureView
		activity.scenario.onActivity { host ->
			view = TextureView(host)
			host.setContentView(view, ViewGroup.LayoutParams(2 * FrameWidth, 2 * FrameHeight))
			val renderers = RenderersFactory { handler, _, _, _, _ ->
				arrayOf<Renderer>(FfmpegVideoRenderer(0, handler, counted, 50) { true })
			}
			player = ExoPlayer.Builder(target, renderers).build()
			player.addListener(object : Player.Listener {
				override fun onPlaybackStateChanged(playbackState: Int) {
					if (playbackState == Player.STATE_ENDED) ended.countDown()
				}

				override fun onPlayerError(error: PlaybackException) {
					failure.set(error)
					ended.countDown()
				}
			})
			player.setVideoTextureView(view)
			player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
			player.playWhenReady = true
			player.prepare()
		}
		assertTrue("playback did not end", ended.await(30, TimeUnit.SECONDS))
		assertNull("playback failed: ${failure.get()}", failure.get())
		instrumentation.waitForIdleSync()

		var mime: String? = null
		var pictures = 0
		var colours = 0
		activity.scenario.onActivity {
			val counters = checkNotNull(counted.counters) { "the renderer was never enabled" }
			counters.ensureUpdated()
			pictures = counters.renderedOutputBufferCount + counters.droppedBufferCount + counters.skippedOutputBufferCount
			mime = player.videoFormat?.sampleMimeType
			colours = coloursSampled(view)
			player.release()
		}
		assertEquals("video/mpeg2", mime)
		assertTrue("45 frames of two fields gave $pictures pictures, not about 90", pictures in 80..92)
		assertTrue("the picture is blank: $colours colours in the samples", colours >= 6)
	}

	/** How many different colours a grid of points over the view's picture holds. */
	private fun coloursSampled(view: TextureView): Int {
		val bitmap = checkNotNull(view.bitmap) { "the texture view holds no picture" }
		val seen = HashSet<Int>()
		for (x in 0 until 16) for (y in 0 until 12) {
			seen += bitmap.getPixel(bitmap.width * (2 * x + 1) / 32, bitmap.height * (2 * y + 1) / 24)
		}
		return seen.size
	}

	/** Keeps the renderer's counters, which it updates as it draws. */
	private class Counted : VideoRendererEventListener {
		@Volatile
		var counters: DecoderCounters? = null

		override fun onVideoEnabled(counters: DecoderCounters) {
			this.counters = counters
		}
	}

	private companion object {
		const val FrameWidth = 352
		const val FrameHeight = 240
	}
}

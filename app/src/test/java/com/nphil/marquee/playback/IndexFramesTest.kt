package com.nphil.marquee.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexFramesTest {
	private val time = ManualTime()
	private val scope = CoroutineScope(Job() + time)
	private var answer: ByteArray? = bifOf(listOf(0L, 10L, 20L, 30L, 40L), listOf("f0", "f1", "f2", "f3", "f4"))
	private var fetches = 0
	private var handedOver = 0
	private var decodes = 0
	private var fetchFails = false
	private var decodeFails = false

	private val pictures = object : Pictures<String> {
		override fun size(picture: String): Int = picture.length

		override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
			check(!decodeFails) { "the frame is damaged" }
			decodes++
			return WordPictures.decode(bytes, offset, length)
		}

		override fun encode(picture: String): ByteArray = WordPictures.encode(picture)
	}

	@After
	fun stopEverything() {
		scope.cancel()
	}

	private fun frames(): IndexFrames<String> {
		val frames = IndexFrames(scope, time, time, pictures, Revision(), RecordingLog()) {
			fetches++
			check(!fetchFails) { "the connection broke" }
			answer
		}
		frames.start { handedOver++ }
		time.runUntilIdle()
		return frames
	}

	@Test
	fun readsTheIndexOnlyOnceThePictureHasPlayedForAWhile() {
		frames()
		time.advance(START_DELAY_MS - 1)
		assertEquals(0, fetches)

		time.advance(1)

		assertEquals(1, fetches)
	}

	@Test
	fun aScrubReadsTheIndexAtOnceAndDecodesOnlyTheFramesItAsksFor() {
		val frames = frames()
		assertNull(frames.frame(25.0))
		frames.want(25.0)
		time.runUntilIdle()

		assertEquals(1, fetches)
		assertEquals("f2", frames.frame(25.0))
		assertEquals(1, decodes)
		// Until its own is decoded, the nearest frame stands in.
		assertEquals("f2", frames.frame(5.0))
		frames.want(5.0)
		time.runUntilIdle()
		assertEquals("f0", frames.frame(5.0))
		assertEquals(2, decodes)
	}

	@Test
	fun handsOverWhenTheServerHasNoIndex() {
		answer = null
		val frames = frames()
		frames.want(25.0)
		time.runUntilIdle()

		assertEquals(1, handedOver)
		assertNull(frames.frame(25.0))
	}

	@Test
	fun handsOverWhenWhatTheServerSendsIsNotAnIndex() {
		answer = "<html>not an index</html>".toByteArray()
		val frames = frames()
		frames.want(25.0)
		time.runUntilIdle()

		assertEquals(1, handedOver)
		assertNull(frames.frame(25.0))
	}

	@Test
	fun handsOverWhenReadingTheIndexFails() {
		fetchFails = true
		val frames = frames()
		frames.want(25.0)
		time.runUntilIdle()

		assertEquals(1, handedOver)
		assertNull(frames.frame(25.0))
	}

	@Test
	fun aFrameThatCannotBeDecodedDoesNotStopTheNext() {
		val frames = frames()
		decodeFails = true
		frames.want(25.0)
		time.runUntilIdle()
		assertNull(frames.frame(25.0))

		decodeFails = false
		frames.want(5.0)
		time.runUntilIdle()

		assertEquals("f0", frames.frame(5.0))
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingAFrameReadsTheIndexAtOnceAndKeepsTheFrame() {
		val frames = frames()
		val loaded = scope.async { frames.load(25.0) }
		time.runUntilIdle()

		assertEquals("f2", loaded.getCompleted())
		assertEquals(1, fetches)
		assertEquals("f2", frames.frame(25.0))
		// Asked again, the frame is not decoded twice.
		val again = scope.async { frames.load(25.0) }
		time.runUntilIdle()
		assertEquals("f2", again.getCompleted())
		assertEquals(1, decodes)
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingGivesNothingWhenTheServerHasNoIndex() {
		answer = null
		val frames = frames()
		val loaded = scope.async { frames.load(25.0) }
		time.runUntilIdle()

		assertNull(loaded.getCompleted())
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun closingEndsALoadStillWaitingForTheIndex() {
		val frames = IndexFrames(scope, time, time, pictures, Revision(), RecordingLog()) { answer }
		val loaded = scope.async { frames.load(25.0) }
		frames.close()
		time.runUntilIdle()

		assertNull(loaded.getCompleted())
	}
}

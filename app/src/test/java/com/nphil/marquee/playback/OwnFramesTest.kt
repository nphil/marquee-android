package com.nphil.marquee.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnFramesTest {
	@get:Rule
	val folder = TemporaryFolder()

	private val time = ManualTime()
	private val scope = CoroutineScope(Job() + time)
	private val source = FakeSource(time)
	private val conditions = FakeConditions()
	private val log = RecordingLog()
	private var playhead = 0.0
	private var rebuffering = false

	@After
	fun stopEverything() {
		scope.cancel()
	}

	/** An engine over [source]. [kept] gives it a disk to fill and a title [durationMs] long to fill it across. */
	private fun engine(kept: Boolean = false, durationMs: Long = 600_000L, source: FrameSource<String> = this.source): OwnFrames<String> {
		val store = if (kept) PictureStore(DiskFrames(folder.root, 1_000_000L), "part", WordPictures, time) else null
		val frames = OwnFrames(scope, time, source, WordPictures, store, Revision(), durationMs, { playhead }, { rebuffering }, conditions, log)
		frames.start()
		time.runUntilIdle()
		return frames
	}

	/** Takes no frame while [hold] is in force, and its next within one look of [release]. */
	private fun assertFillWaits(hold: () -> Unit, release: () -> Unit) {
		engine(kept = true, durationMs = 40_000L)
		hold()
		time.advance(START_DELAY_MS + 3 * OwnFrames.POLL_MS)
		assertTrue(source.asked.isEmpty())

		release()
		time.advance(OwnFrames.POLL_MS)

		assertEquals(1, source.asked.size)
	}

	@Test
	fun asksTheFileOnlyForWhereTheThumbCameToRest() {
		val frames = engine()
		frames.want(10.0)
		time.advance(30)
		frames.want(20.0)
		time.advance(30)
		frames.want(50.0)
		assertTrue(source.asked.isEmpty())

		time.advance(OwnFrames.DEBOUNCE_MS)

		assertEquals(listOf(50_000L), source.asked)
		assertEquals("frame@50000", frames.frame(50.0))
	}

	@Test
	fun aFrameAlreadyBeingMadeIsKeptAndOnlyTheNewestRequestFollowsIt() {
		val slow = FakeSource(time, cost = 200)
		val frames = engine(source = slow)
		frames.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)
		frames.want(100.0)
		time.advance(10)
		frames.want(300.0)
		time.advance(200)

		assertEquals(listOf(10_000L, 300_000L), slow.asked)
		assertEquals("frame@10000", frames.frame(10.0))
	}

	@Test
	fun endingTheScrubDropsTheRequestStillWaiting() {
		val frames = engine()
		frames.want(10.0)
		time.advance(30)
		frames.settle()
		time.advance(1_000)

		assertTrue(source.asked.isEmpty())
	}

	@Test
	fun showsTheNearestFrameMadeWithinThirtySeconds() {
		val frames = engine()
		assertNull(frames.frame(10.0))
		frames.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)
		frames.want(60.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		assertEquals("frame@10000", frames.frame(2.0))
		// Level between the two: the earlier.
		assertEquals("frame@10000", frames.frame(35.0))
		assertEquals("frame@60000", frames.frame(37.5))
		assertNull(frames.frame(200.0))
	}

	@Test
	fun reportsWhatEachFrameCost() {
		val frames = engine()
		frames.want(50.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		val line = log.events.single { it.first == "scrub.frame" }.second
		assertEquals(10, line["slot"])
		assertEquals(false, line["fill"])
		assertEquals(1, line["frames"])
	}

	@Test
	fun neverMakesTwoFramesAtOnce() {
		val slow = FakeSource(time, cost = 200)
		playhead = 12.0
		val frames = engine(kept = true, durationMs = 40_000L, source = slow)
		time.advance(START_DELAY_MS + 50)
		frames.want(0.0)
		time.advance(500)

		assertEquals(listOf(10_000L, 0L), slow.asked)
		assertEquals(1, slow.mostAtOnce)
		assertEquals("frame@0", frames.frame(0.0))
	}

	@Test
	fun givesUpOnAFileThatGivesNoFrameThreeTimesRunning() {
		source.fails = true
		val frames = engine()
		for (seconds in listOf(10.0, 20.0, 30.0, 40.0)) {
			frames.want(seconds)
			time.advance(OwnFrames.DEBOUNCE_MS)
		}

		assertEquals(3, source.asked.size)
		assertEquals(1, source.closes)
	}

	@Test
	fun letsTheFileGoWhenNothingHasAskedForAWhile() {
		val frames = engine()
		frames.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)
		assertEquals(0, source.closes)

		time.advance(OwnFrames.IDLE_RELEASE_MS)

		assertEquals(1, source.closes)
	}

	@Test
	fun fillsEveryTenSecondsWorkingOutwardFromThePictureAndStopsAtTheEnd() {
		playhead = 12.0
		engine(kept = true, durationMs = 40_000L)

		time.advance(START_DELAY_MS)
		assertEquals(listOf(10_000L), source.asked)
		time.advance(3 * OwnFrames.FILL_PAUSE_MS)
		assertEquals(listOf(10_000L, 20_000L, 0L, 30_000L), source.asked)

		// Nothing is left, so the file is let go and the fill is over.
		time.advance(OwnFrames.FILL_PAUSE_MS)
		assertEquals(1, source.closes)
		assertEquals(4, source.asked.size)
	}

	@Test
	fun aFrameTheFillKeptOnDiskIsShownWithoutAskingTheFileAgain() {
		playhead = 12.0
		val frames = engine(kept = true, durationMs = 40_000L)
		time.advance(START_DELAY_MS)
		assertNull(frames.frame(10.0))
		val before = frames.revision

		frames.want(10.0)
		time.runUntilIdle()

		assertEquals("frame@10000", frames.frame(10.0))
		assertNotEquals(before, frames.revision)
		assertEquals(1, source.asked.size)
	}

	@Test
	fun aTitleOpenedAgainShowsItsKeptFramesWithoutAskingTheFile() {
		val first = engine(kept = true)
		first.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)
		first.close()

		val again = FakeSource(time)
		val frames = engine(kept = true, source = again)
		assertNull(frames.frame(10.0))
		frames.want(10.0)
		time.runUntilIdle()

		assertEquals("frame@10000", frames.frame(10.0))
		assertTrue(again.asked.isEmpty())
	}

	@Test
	fun theFillWaitsWhileTheScrubIsUnderWay() {
		playhead = 12.0
		val frames = engine(kept = true, durationMs = 40_000L)
		frames.want(0.0)
		time.advance(START_DELAY_MS + 3 * OwnFrames.POLL_MS)
		assertEquals(listOf(0L), source.asked)

		frames.settle()
		time.advance(OwnFrames.POLL_MS)

		assertEquals(listOf(0L, 10_000L), source.asked)
	}

	@Test
	fun theFillKeepsAwayForAWhileAfterThePictureStalls() {
		rebuffering = true
		engine(kept = true, durationMs = 40_000L)
		time.advance(START_DELAY_MS + 5_000)
		assertTrue(source.asked.isEmpty())

		rebuffering = false
		time.advance(OwnFrames.STALL_REST_MS - OwnFrames.POLL_MS)
		assertTrue(source.asked.isEmpty())
		time.advance(OwnFrames.POLL_MS)

		assertEquals(0L, source.asked.first())
	}

	@Test
	fun theFillWaitsOnAMeteredNetwork() {
		assertFillWaits({ conditions.unmeteredNow = false }, { conditions.unmeteredNow = true })
	}

	@Test
	fun theFillWaitsWhileTheDeviceIsWarm() {
		assertFillWaits({ conditions.coolNow = false }, { conditions.coolNow = true })
	}

	@Test
	fun theFillWaitsWhileTheScreenIsOff() {
		assertFillWaits({ conditions.screenOnNow = false }, { conditions.screenOnNow = true })
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingMakesTheFrameAtOnceWithoutAScrub() {
		val frames = engine()
		val loaded = scope.async { frames.load(50.0) }
		time.runUntilIdle()

		assertEquals("frame@50000", loaded.getCompleted())
		assertEquals(listOf(50_000L), source.asked)
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingAFrameThatIsKeptDoesNotAskTheFileAgain() {
		val frames = engine()
		scope.async { frames.load(50.0) }
		time.runUntilIdle()
		val again = scope.async { frames.load(50.0) }
		time.runUntilIdle()

		assertEquals("frame@50000", again.getCompleted())
		assertEquals(1, source.asked.size)
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingGivesNothingWhenTheFileGivesNoFrame() {
		source.fails = true
		val frames = engine()
		val loaded = scope.async { frames.load(50.0) }
		time.runUntilIdle()

		assertNull(loaded.getCompleted())
	}
}

package com.nphil.marquee.playback

import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewRoutingTest {
	@get:Rule
	val folder = TemporaryFolder()

	private val time = ManualTime()
	private val scope = CoroutineScope(Job() + time)
	private lateinit var platform: FakePlatform

	@Before
	fun setUp() {
		platform = FakePlatform(time, folder.root)
	}

	@After
	fun stopEverything() {
		scope.cancel()
	}

	private fun previewsOf(plan: PlayPlan, stream: PlayStream) = previewsFor(platform, scope, plan, stream, { 0.0 }, { false })

	@Test
	fun aServerConversionWithoutAnIndexOffersNoPreviews() {
		assertNull(previewsOf(filmPlan(), hlsStream()))
		assertEquals(0, platform.opened)
		assertEquals(0, platform.indexRequests)
	}

	@Test
	fun aFileWithoutAnIndexIsReadForItsOwnFrames() {
		val previews = previewsOf(filmPlan(), fileStream())!!
		previews.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		assertEquals("frame@10000", previews.frame(10.0))
		assertEquals(0, platform.indexRequests)
	}

	@Test
	fun theServersIndexIsUsedForAConversionWithoutOpeningAFile() {
		platform.index = bifOf(listOf(0L, 10L, 20L), listOf("f0", "f1", "f2"))
		val previews = previewsOf(filmPlan(index = true), hlsStream())!!
		previews.want(15.0)
		time.runUntilIdle()

		assertEquals("f1", previews.frame(15.0))
		assertEquals(0, platform.opened)
	}

	@Test
	fun aFileWhoseIndexIsUnusableFallsThroughToItsOwnFramesAtOnce() {
		val previews = previewsOf(filmPlan(index = true), fileStream())!!
		previews.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		assertEquals(1, platform.indexRequests)
		assertEquals("frame@10000", previews.frame(10.0))
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingAConversionsFrameComesFromTheServersIndex() {
		platform.index = bifOf(listOf(0L, 10L, 20L), listOf("f0", "f1", "f2"))
		val previews = previewsOf(filmPlan(index = true), hlsStream())!!
		val loaded = scope.async { previews.load(15.0) }
		time.runUntilIdle()

		assertEquals("f1", loaded.getCompleted())
		assertEquals(0, platform.opened)
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun loadingFromAFileWhoseIndexIsUnusableReadsTheFileItself() {
		val previews = previewsOf(filmPlan(index = true), fileStream())!!
		val loaded = scope.async { previews.load(10.0) }
		time.runUntilIdle()

		assertEquals("frame@10000", loaded.getCompleted())
	}

	@Test
	fun aConversionWhoseIndexIsUnusableHasNothingToShow() {
		val previews = previewsOf(filmPlan(index = true), hlsStream())!!
		previews.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		assertNull(previews.frame(10.0))
		assertEquals(0, platform.opened)
	}

	@Test
	fun closingLetsGoOfTheFileAndStopsTheWork() {
		val previews = previewsOf(filmPlan(), fileStream())!!
		previews.want(10.0)
		time.advance(OwnFrames.DEBOUNCE_MS)

		previews.close()
		time.advance(START_DELAY_MS + OwnFrames.IDLE_RELEASE_MS)

		assertEquals(1, platform.source.closes)
		assertEquals(listOf(10_000L), platform.source.asked)
	}

	@Test
	fun thePlayheadIsReadOnTheThreadOfTheScopeGiven() {
		val screen = ManualTime()
		val screenScope = CoroutineScope(Job() + screen)
		var onScreen = false
		val playhead = {
			onScreen = screen.running
			0.0
		}
		val previews = previewsFor(platform, screenScope, filmPlan(), fileStream(), playhead, { false })!!

		time.advance(START_DELAY_MS)
		// Each read of the player is a round trip: the screen's thread answers, then the engine's goes on.
		repeat(3) {
			screen.runUntilIdle()
			time.runUntilIdle()
		}

		assertTrue(onScreen)
		previews.close()
		screenScope.cancel()
	}
}

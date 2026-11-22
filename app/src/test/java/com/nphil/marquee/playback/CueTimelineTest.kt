package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTimelineTest {
	private fun timeline(vararg spans: LongRange) = CueTimeline(
		LongArray(spans.size) { spans[it].first },
		LongArray(spans.size) { spans[it].last },
	)

	@Test
	fun aCueShowsFromItsStartUpToButNotIncludingItsEnd() {
		val timeline = timeline(1_000L..2_000L)
		assertEquals(emptyList<Int>(), timeline.at(999))
		assertEquals(listOf(0), timeline.at(1_000))
		assertEquals(listOf(0), timeline.at(1_999))
		assertEquals(emptyList<Int>(), timeline.at(2_000))
	}

	@Test
	fun cuesThatFollowOneAnotherNeverShowTogether() {
		val timeline = timeline(0L..1_000L, 1_000L..2_000L)
		assertEquals(listOf(0), timeline.at(999))
		assertEquals(listOf(1), timeline.at(1_000))
	}

	@Test
	fun cuesThatOverlapShowTogetherInStartOrder() {
		val timeline = timeline(0L..2_000L, 500L..1_000L)
		assertEquals(listOf(0, 1), timeline.at(700))
		assertEquals(listOf(0), timeline.at(1_500))
	}

	@Test
	fun aLongCueIsFoundAgainAfterShortOnesThatCameInsideIt() {
		val timeline = timeline(0L..10_000L, 100L..150L, 200L..250L, 300L..350L)
		assertEquals(listOf(0, 1), timeline.at(120))
		assertEquals(listOf(0), timeline.at(5_000))
		assertEquals(emptyList<Int>(), timeline.at(10_000))
	}

	@Test
	fun noCuesShowNothing() {
		assertEquals(emptyList<Int>(), timeline().at(0))
	}
}

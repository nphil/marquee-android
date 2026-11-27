package com.nphil.marquee.playback

import com.nphil.marquee.core.Marker
import com.nphil.marquee.core.PlexItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffersTest {
	private val next = PlexItem(ratingKey = "9", title = "Next")
	private val intro = listOf(Marker(start = 30_000, end = 90_000))
	private val credits = listOf(Marker(start = 2_400_000, end = 2_500_000, isFinal = true))

	@Test
	fun skipIntroShowsInsideTheIntroUntilASecondBeforeItsEnd() {
		assertNull(offerAt(29.9, intro, credits, false, emptySet(), next))
		assertEquals(Offer.SkipIntro(90.0), offerAt(30.0, intro, credits, false, emptySet(), next))
		assertEquals(Offer.SkipIntro(90.0), offerAt(88.9, intro, credits, false, emptySet(), next))
		assertNull(offerAt(89.0, intro, credits, false, emptySet(), next))
	}

	@Test
	fun anIntroThatWasSkippedDoesNotComeBack() {
		assertNull(offerAt(40.0, intro, credits, true, emptySet(), next))
	}

	@Test
	fun splitCreditsAllowNextOnEitherMarkerButNotDuringTheSceneBetweenThem() {
		val early = Marker(100_000, 120_000)
		val final = credits.single()
		val segments = listOf(early, final)
		fun at(s: Double, dismissed: Set<Int> = emptySet()) = offerAt(s, intro, segments, false, dismissed, next)
		assertEquals(Offer.UpNext(next, early), at(100.0))
		assertNull(at(101.0, setOf(early.start)))
		assertNull(at(120.0))
		assertEquals(Offer.UpNext(next, final), at(2_400.0, setOf(early.start)))
	}

	@Test
	fun onlyASeenMarkerCanCarryManualNextDuringTheGag() {
		val seen = Marker(100_000, 120_000)
		fun at(s: Double, dismissed: Set<Int> = emptySet()) =
			offerAt(s, emptyList(), listOf(seen), false, dismissed, next, firstSeenCredits = seen) as? Offer.UpNext
		assertEquals(true, at(119.99)?.canCountDown)
		assertEquals(false, at(120.0)?.canCountDown)
		assertNull(at(99.0))
		assertNull(at(121.0, setOf(seen.start)))
		val chapter = seen.copy(fromChapter = true)
		assertEquals(false, (offerAt(110.0, emptyList(), listOf(chapter), false, emptySet(), next) as Offer.UpNext).canCountDown)
	}

	@Test
	fun standaloneCreditsSkipOnlyTheMarkedSpanUnlessItEndsTheFilm() {
		val early = Marker(100_000, 120_000)
		val tail = Marker(220_000, 239_000)
		fun at(s: Double) = offerAt(s, emptyList(), listOf(early, tail), false, emptySet(), null, duration = 240.0)
		assertEquals(Offer.SkipCredits(early, false), at(110.0))
		assertNull(at(120.0))
		assertEquals(Offer.SkipCredits(tail, true), at(239.5))
		assertNull(offerAt(239.5, emptyList(), emptyList(), false, emptySet(), null, duration = 240.0))
	}
}

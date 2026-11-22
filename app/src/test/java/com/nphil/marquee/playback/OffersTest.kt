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
		assertNull(offerAt(29.9, intro, credits, false, false, next))
		assertEquals(Offer.SkipIntro(90.0), offerAt(30.0, intro, credits, false, false, next))
		assertEquals(Offer.SkipIntro(90.0), offerAt(88.9, intro, credits, false, false, next))
		assertNull(offerAt(89.0, intro, credits, false, false, next))
	}

	@Test
	fun anIntroThatWasSkippedDoesNotComeBack() {
		assertNull(offerAt(40.0, intro, credits, true, false, next))
	}

	@Test
	fun upNextShowsFromTheFinalCreditsMarkerWhenAnEpisodeFollows() {
		assertNull(offerAt(2_399.0, intro, credits, false, false, next))
		assertEquals(Offer.UpNext(next), offerAt(2_400.0, intro, credits, false, false, next))
		assertNull("a movie has no episode to go on to", offerAt(2_450.0, intro, credits, false, false, null))
		assertNull("Watch Credits keeps it away", offerAt(2_450.0, intro, credits, false, true, next))

		val several = listOf(Marker(100_000, 120_000), Marker(2_400_000, 2_500_000, isFinal = true), Marker(2_600_000, 2_700_000))
		assertNull("only the final marker starts Up Next", offerAt(110.0, emptyList(), several, false, false, next))
		val noneFinal = listOf(Marker(100_000, 120_000), Marker(2_600_000, 2_700_000))
		assertEquals("with none marked final, the last one does", Offer.UpNext(next), offerAt(2_600.0, emptyList(), noneFinal, false, false, next))
	}
}

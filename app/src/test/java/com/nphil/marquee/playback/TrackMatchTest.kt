package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchTest {
	@Test
	fun whenThePlayerListsAsManyTracksAsCoreTheyAreTheSamePlaces() {
		assertEquals(1, matchTrack(listOf("en", "ja"), 1, listOf("ja", "en")))
	}

	@Test
	fun whenTheCountsDifferTheSamePlaceAmongTheTracksOfThatLanguageIsTaken() {
		val core = listOf("en", "ja", "en")
		assertEquals(2, matchTrack(core, 2, listOf("ja", "en", "en", "fr")))
		assertEquals(1, matchTrack(core, 0, listOf("ja", "en", "en", "fr")))
	}

	@Test
	fun withFewerTracksOfThatLanguageThanItsPlaceTheFirstOfThemIsTaken() {
		assertEquals(1, matchTrack(listOf("en", "en"), 1, listOf("ja", "en", "fr")))
	}

	@Test
	fun noTrackIsTakenWhenThePlayerHasNoneOfTheLanguageOrTheLanguageIsUnknown() {
		assertNull(matchTrack(listOf("en", "ja"), 0, listOf("fr")))
		assertNull(matchTrack(listOf(null, "en"), 0, listOf("en")))
	}

	@Test
	fun aRegionDoesNotMakeAnotherLanguage() {
		assertTrue(sameLanguage("en", "en-US"))
		assertTrue(sameLanguage("EN", "en"))
		assertFalse(sameLanguage("en", "ja"))
	}

	@Test
	fun anUnknownLanguageIsNeverTheSameAsAnything() {
		assertFalse(sameLanguage(null, null))
		assertFalse(sameLanguage("", ""))
		assertFalse(sameLanguage("und", "und"))
	}
}

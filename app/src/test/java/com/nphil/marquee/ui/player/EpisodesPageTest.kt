package com.nphil.marquee.ui.player

import com.nphil.marquee.core.PlexItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodesPageTest {
	private fun episode(index: Int?, title: String = "Pilot", duration: Int = 45 * 60_000, viewOffset: Int = 0, watched: Boolean = false) =
		PlexItem(ratingKey = "e$index", index = index, title = title, duration = duration, viewOffset = viewOffset, isWatched = watched)

	@Test
	fun theTitleLeadsWithTheNumber() {
		assertEquals("3 · Pilot", episodeTitle(episode(3)))
		assertEquals("Pilot", episodeTitle(episode(null)))
	}

	@Test
	fun theStatusIsPlayingThenWatchedThenTimeLeftThenLength() {
		assertEquals("Now playing", episodeStatus(episode(1, viewOffset = 60_000, watched = true), playing = true))
		assertEquals("Watched", episodeStatus(episode(1, watched = true), playing = false))
		assertEquals("40 min left", episodeStatus(episode(1, viewOffset = 5 * 60_000), playing = false))
		assertEquals("45 min", episodeStatus(episode(1), playing = false))
		assertNull(episodeStatus(episode(1, duration = 0), playing = false))
	}

	@Test
	fun theDescriptionReadsLikeASentence() {
		assertEquals("Episode 2, Pilot, Watched", episodeDescription(episode(2, watched = true), playing = false))
		assertEquals("Pilot, 45 min", episodeDescription(episode(null), playing = false))
	}

	@Test
	fun theListOpensOneRowAboveThePlayingEpisode() {
		val season = (1..6).map { episode(it) }
		assertEquals(3, firstVisibleEpisode(season, "e5"))
		assertEquals(0, firstVisibleEpisode(season, "e1"))
		assertEquals(0, firstVisibleEpisode(season, "other"))
		assertEquals(0, firstVisibleEpisode(season, null))
	}
}

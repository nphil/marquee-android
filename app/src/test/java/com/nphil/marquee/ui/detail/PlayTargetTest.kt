package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTargetTest {
	private val hour = 3_600_000

	private fun film(offset: Int = 0) = PlexItem(ratingKey = "f", type = MediaType.Movie, duration = 2 * hour, viewOffset = offset)

	private fun episode(key: String, number: Int, offset: Int = 0, watched: Boolean = false) = PlexItem(
		ratingKey = key, type = MediaType.Episode, parentIndex = 2, index = number, duration = hour, viewOffset = offset, isWatched = watched,
	)

	@Test
	fun aFilmPlaysItselfAndResumesWithTheTimeLeft() {
		val fresh = film()
		assertEquals("Play", playLabel(fresh, fresh))
		val begun = film(offset = hour + hour / 2)
		assertEquals("Resume · 30 min left", playLabel(begun, begun))
	}

	@Test
	fun aFilmNearTheEndPlaysFromTheStartAgain() {
		val almost = film(offset = (1.97 * hour).toInt())
		assertFalse(almost.isResumable)
		assertEquals("Play", playLabel(almost, almost))
	}

	@Test
	fun aShowPlaysTheEpisodeUpNext() {
		val show = PlexItem(ratingKey = "s", type = MediaType.Show, onDeck = episode("e5", 5, offset = hour / 2))
		val target = playTarget(show, DetailState())
		assertEquals("e5", target?.ratingKey)
		assertEquals("Resume S2 · E5", playLabel(target!!, show))
		assertTrue(target.isResumable)
	}

	@Test
	fun aSeasonPlaysTheEpisodeInProgressElseTheFirstUnwatched() {
		val season = PlexItem(ratingKey = "n", type = MediaType.Season)
		val seen = episode("e1", 1, watched = true)
		val next = episode("e2", 2)
		assertEquals("e2", playTarget(season, DetailState(episodes = listOf(seen, next)))?.ratingKey)
		val partway = episode("e3", 3, offset = hour / 4)
		assertEquals("e3", playTarget(season, DetailState(episodes = listOf(seen, next, partway)))?.ratingKey)
		assertEquals("Play S2 · E2", playLabel(next, season))
	}

	@Test
	fun aSeasonStillLoadingHasNothingToPlay() {
		assertNull(playTarget(PlexItem(ratingKey = "n", type = MediaType.Season), DetailState(episodes = null)))
	}
}

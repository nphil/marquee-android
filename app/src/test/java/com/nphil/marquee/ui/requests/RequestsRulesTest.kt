package com.nphil.marquee.ui.requests

import com.nphil.marquee.core.SeerrBadge
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrCatalogue
import com.nphil.marquee.core.SeerrDetails
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrNamed
import com.nphil.marquee.core.SeerrRequest
import com.nphil.marquee.core.SeerrSeason
import com.nphil.marquee.core.SeerrStreaming
import com.nphil.marquee.core.SeerrTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestsRulesTest {
	private val movie = SeerrTitle("movie/1", SeerrKind.Movie, 1, "Wing It!", year = 2024)
	private val series = SeerrTitle("tv/2", SeerrKind.Tv, 2, "Sintel")
	private val blocked = SeerrBadge("pending", "Pending")

	private fun seasons(vararg requestable: Boolean) =
		requestable.mapIndexed { i, ok -> SeerrSeason(i + 1, "Season ${i + 1}", 8, badge = if (ok) null else blocked) }

	@Test
	fun fourKStartsOnlyWhenItIsTheOneThingThatCanBeAsked() {
		assertTrue(RequestsRules.starts4K(SeerrDetails(movie, canRequest4K = true)))
		assertFalse(RequestsRules.starts4K(SeerrDetails(movie, canRequest = true, canRequest4K = true)))
		assertFalse(RequestsRules.starts4K(SeerrDetails(movie, canRequest = true)))
	}

	@Test
	fun aSeriesStartsWithEveryRequestableSeasonOfTheQuality() {
		val details = SeerrDetails(series, seasonChoices = seasons(true, false, true), seasonChoices4K = seasons(false, true))
		assertEquals(setOf(1, 3), RequestsRules.startingSeasons(details, is4K = false))
		assertEquals(setOf(2), RequestsRules.startingSeasons(details, is4K = true))
	}

	@Test
	fun sendNeedsAQualityAndForPartialSeriesASeason() {
		val tv = SeerrDetails(series, canRequest = true, seasonChoices = seasons(true))
		assertFalse(RequestsRules.canSend(tv, is4K = false, chosen = emptySet()))
		assertTrue(RequestsRules.canSend(tv, is4K = false, chosen = setOf(1)))
		assertFalse(RequestsRules.canSend(tv, is4K = true, chosen = setOf(1)))
		assertTrue(RequestsRules.canSend(tv.copy(partialRequests = false), is4K = false, chosen = emptySet()))
		assertTrue(RequestsRules.canSend(SeerrDetails(movie, canRequest = true), is4K = false, chosen = emptySet()))
	}

	@Test
	fun theStreamingRegionFallsBackFromTheFilterToTheDeviceToUs() {
		val regions = listOf(SeerrNamed("GB", "United Kingdom"), SeerrNamed("US", "United States"))
		val query = SeerrBrowseQuery(SeerrKind.Movie)
		assertEquals("FR", RequestsRules.effectiveRegion(query.copy(streaming = SeerrStreaming(8, "FR")), "GB", "GB", regions))
		assertEquals("DE", RequestsRules.effectiveRegion(query, "DE", "GB", regions))
		assertEquals("GB", RequestsRules.effectiveRegion(query, null, "GB", regions))
		assertEquals("US", RequestsRules.effectiveRegion(query, null, "JP", regions))
	}

	@Test
	fun chipsFollowTheViewMenuAndEachOneTurnsItsFilterOff() {
		val catalogue = SeerrCatalogue(
			rows = emptyList(),
			sorts = emptyList(),
			eras = listOf(SeerrCatalogue.Era("2020s", "2020s")),
			runtimes = listOf(SeerrCatalogue.Runtime("short", "Under 90 min", "Under 30 min")),
		)
		val query = SeerrBrowseQuery(
			SeerrKind.Tv, upcoming = true, era = "2020s", language = "fr", minRating = 7, runtime = "short",
			streaming = SeerrStreaming(8, "GB"),
		)
		val chips = RequestsRules.chips(query, catalogue, listOf(SeerrNamed("fr", "French")), providerName = "Netflix")
		assertEquals(
			listOf("Upcoming", "2020s", "French", "Rating 7+", "Under 30 min", "Netflix · GB"),
			chips.map { it.text },
		)
		val cleared = chips.fold(query) { q, chip -> RequestsRules.without(q, chip.filter) }
		assertFalse(cleared.hasMenuFilters)
		assertEquals(SeerrKind.Tv, cleared.kind)
	}

	@Test
	fun aRequestLineNamesSeasonsAndQuality() {
		fun request(seasons: List<Int>, is4K: Boolean = false) = SeerrRequest(1, SeerrKind.Tv, seasons = seasons, is4K = is4K)
		assertEquals("Season 2", RequestsRules.requestLine(request(listOf(2))))
		assertEquals("Seasons 1, 3 · 4K", RequestsRules.requestLine(request(listOf(3, 1), is4K = true)))
		assertEquals("4K", RequestsRules.requestLine(SeerrRequest(1, SeerrKind.Movie, is4K = true)))
		assertNull(RequestsRules.requestLine(SeerrRequest(1, SeerrKind.Movie)))
	}

	@Test
	fun theMetaLineLeavesOutWhatSeerrDidNotGive() {
		val full = SeerrDetails(movie, genres = listOf("Comedy", "Drama"), runtime = 98)
		assertEquals("2024 · 98 min · Comedy, Drama", RequestsRules.metaLine(full))
		assertEquals("", RequestsRules.metaLine(SeerrDetails(series, runtime = 0)))
	}
}

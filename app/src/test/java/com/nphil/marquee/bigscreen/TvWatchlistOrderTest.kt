package com.nphil.marquee.bigscreen

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TvWatchlistOrderTest {
	private val first = PlexItem("a", type = MediaType.Movie, title = "The Winner", titleSort = "Winner", rating = 8.0)
	private val second = PlexItem("b", type = MediaType.Show, title = "Tower", rating = 5.0)
	private val missing = PlexItem("c", type = MediaType.Movie, title = "Zero")
	private val rows = listOf(missing, first, second)

	@Test fun releasedUsesCatalogueMembershipNotDatesAndKeepsAddedOrder() {
		val a = first.copy(guid = "plex://movie/a", originallyAvailableAt = null)
		val b = second.copy(guid = "plex://show/b")
		val c = missing.copy(guid = "plex://movie/c", originallyAvailableAt = "2001-01-01")
		val items = listOf(b, c, a)
		val released = setOf(a.guid!!, b.guid!!)
		val filter = TvWatchlistOrder(releasedOnly = true)
		assertEquals(listOf(b, a), filter.apply(items, released))
		assertEquals(listOf(a), filter.copy(type = MediaType.Movie).apply(items, released))
		assertEquals(emptyList<PlexItem>(), filter.apply(items, emptySet()))
	}

	@Test fun reversingDateAddedReversesServiceOrderAfterFiltering() {
		assertEquals(listOf(first, missing), TvWatchlistOrder(reversed = true, type = MediaType.Movie).apply(rows))
	}
	@Test fun bothRatingDirectionsPutUnknownLast() {
		assertEquals(listOf(first, second, missing), TvWatchlistOrder(TvWatchlistSort.Rating).apply(rows))
		assertEquals(listOf(second, first, missing), TvWatchlistOrder(TvWatchlistSort.Rating, true).apply(rows))
	}
	@Test fun titleSortUsesTheLibrarySortTitle() {
		assertEquals(listOf(second, first, missing), TvWatchlistOrder(TvWatchlistSort.Title).apply(rows))
	}
}

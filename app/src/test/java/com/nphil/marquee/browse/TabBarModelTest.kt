package com.nphil.marquee.browse

import com.nphil.marquee.core.Destination.Downloads
import com.nphil.marquee.core.Destination.Home
import com.nphil.marquee.core.Destination.Library
import com.nphil.marquee.core.Destination.LiveTv
import com.nphil.marquee.core.Destination.Requests
import com.nphil.marquee.core.Destination.Search
import com.nphil.marquee.core.Destination.Watchlist
import com.nphil.marquee.core.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TabBarModelTest {
	@Test
	fun theRowsAreTheDestinationsTheAppHasWithoutSearch() {
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Requests, Downloads), TabLayout.standard.movable())
	}

	@Test
	fun aRowMovesAndSearchStaysLast() {
		val moved = TabLayout.standard.movedRow(from = 5, to = 1)

		assertEquals(listOf(Home, Downloads, Library, LiveTv, Watchlist, Requests), moved.movable())
		assertEquals(Search, moved.order.last())
	}

	@Test
	fun aMoveOutsideTheRowsChangesNothing() {
		val layout = TabLayout.standard

		assertSame(layout, layout.movedRow(from = 0, to = layout.movable().size))
		assertSame(layout, layout.movedRow(from = -1, to = 0))
		assertSame(layout, layout.movedRow(from = 2, to = 2))
	}

	@Test
	fun eachRowSaysWhereItOpensFromAndRequestsWhatItNeeds() {
		assertEquals("Always a tab.", tabNote(Home, available = true))
		assertEquals("Otherwise a button at the top of the Library.", tabNote(Search, available = true))
		assertEquals("Needs Seerr, set up in Settings.", tabNote(Requests, available = false))
	}
}

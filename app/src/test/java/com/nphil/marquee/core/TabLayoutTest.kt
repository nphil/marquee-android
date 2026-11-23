package com.nphil.marquee.core

import com.nphil.marquee.core.Destination.Downloads
import com.nphil.marquee.core.Destination.Home
import com.nphil.marquee.core.Destination.Library
import com.nphil.marquee.core.Destination.LiveTv
import com.nphil.marquee.core.Destination.Requests
import com.nphil.marquee.core.Destination.Search
import com.nphil.marquee.core.Destination.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLayoutTest {
	private val all = Destination.entries.toSet()

	@Test
	fun theSidebarShowsEveryTabSwitchedOnWithSearchLast() {
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Search), TabLayout.standard.tabs(TabLayout.available(false)))
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Requests, Search), TabLayout.standard.tabs(TabLayout.available(true)))
	}

	@Test
	fun aPhonesBarGivesUpTheLastTabsBeforeSearchPastItsLimit() {
		val seerr = TabLayout.available(true)
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Search), TabLayout.standard.tabs(seerr, limit = 5))
		assertEquals(listOf(Home, Library, Search), TabLayout.standard.tabs(seerr, limit = 3))
	}

	@Test
	fun aPhonesBarHoldsOneTabPer72DpFromThreeToFive() {
		assertEquals(5, TabLayout.phoneLimit(599f))
		assertEquals(5, TabLayout.phoneLimit(360f))
		assertEquals(4, TabLayout.phoneLimit(320f))
		assertEquals(3, TabLayout.phoneLimit(150f))
	}

	@Test
	fun homeIsAlwaysATabAndUnavailableDestinationsAreNot() {
		val layout = TabLayout(order = listOf(Requests, Home, Library), shown = setOf(Requests, Library))

		assertEquals(listOf(Home, Library), layout.tabs(all - Requests))
		assertEquals(listOf(Requests, Home, Library), layout.tabs(all))
	}

	@Test
	fun aDestinationBecomingAvailablePastALimitGivesWayBeforeSearch() {
		val layout = TabLayout(
			order = listOf(Home, Library, Requests, LiveTv, Watchlist),
			shown = setOf(Library, Requests, LiveTv, Watchlist, Search),
		)

		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Search), layout.tabs(all - Requests, limit = 5))
		assertEquals(listOf(Home, Library, Requests, LiveTv, Search), layout.tabs(all, limit = 5))
		assertEquals(listOf(Home, Library, Requests, LiveTv, Watchlist, Search), layout.tabs(all))
	}

	@Test
	fun aSwitchChangesOnlyWithRoomAndNeverForHomeOrAnUnavailableDestination() {
		val built = TabLayout.available(seerrEnabled = true)
		val roomy = TabLayout(Destination.entries, setOf(Home, Library, Watchlist, Search))

		assertFalse(roomy.canSwitch(Home, built, limit = 5))
		assertTrue(roomy.canSwitch(LiveTv, built, limit = 5))
		assertTrue(roomy.canSwitch(Requests, built, limit = 5))
		assertFalse(roomy.canSwitch(Requests, all - Requests, limit = 5))
		assertEquals(listOf(Home, Library, Watchlist, Requests, Search), roomy.withTab(Requests, true, built, limit = 5).tabs(built, limit = 5))
		// Five tabs fill a phone's bar: an off one cannot come on, an on one can still go off.
		val full = roomy.withTab(Requests, true, built, limit = 5)
		assertFalse(full.canSwitch(LiveTv, all, limit = 5))
		assertTrue(full.canSwitch(Library, all, limit = 5))
		assertEquals(listOf(Home, Watchlist, Requests, Search), full.withTab(Library, false, built, limit = 5).tabs(built, limit = 5))
		// The sidebar has no bar to fill.
		assertTrue(full.canSwitch(LiveTv, all))
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Requests, Search), full.withTab(LiveTv, true, built).tabs(built))
	}

	@Test
	fun aReorderKeepsSearchLastAndRequestsOnlyWhenSeerrIsOn() {
		val moved = TabLayout.standard.reordered(listOf(Search, Watchlist, Home, Library))

		assertEquals(listOf(Watchlist, Home, Library), moved.order.take(3))
		assertEquals(Search, moved.order.last())
		assertEquals(setOf(Home, Library, LiveTv, Watchlist, Search, Downloads), TabLayout.available(seerrEnabled = false))
		assertEquals(Requests in TabLayout.available(seerrEnabled = true), true)
	}

	@Test
	fun aSavedLayoutReadsBackAndDropsNamesThisVersionDoesNotKnow() {
		val layout = TabLayout.standard.reordered(listOf(Watchlist, Home, Library)).withTab(Library, false, TabLayout.available(true))

		assertEquals(layout, TabLayout.saved(layout.savedOrder, layout.savedShown))
		val later = TabLayout.saved(listOf("Nope", "Library"), listOf("Library", "Nope"))
		assertEquals(Library, later.order.first())
		assertEquals(setOf(Home, Library), later.shown)
	}
}

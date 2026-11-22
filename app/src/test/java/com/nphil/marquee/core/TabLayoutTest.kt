package com.nphil.marquee.core

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
	fun theTabletsBarIsWhatIsBuiltWithSearchLastAndAtMostFive() {
		val built = setOf(Home, Library, Watchlist, Search)

		assertEquals(listOf(Home, Library, Watchlist, Search), TabLayout.standard.tabs(built))
		assertEquals(listOf(Home, Library, Watchlist, Requests, Search), TabLayout.standard.tabs(built + Requests))
		// Every destination built: the sixth tab gives way before Search.
		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Search), TabLayout.standard.tabs(all))
	}

	@Test
	fun homeIsAlwaysATabAndUnavailableDestinationsAreNot() {
		val layout = TabLayout(order = listOf(Requests, Home, Library), shown = setOf(Requests, Library))

		assertEquals(listOf(Home, Library), layout.tabs(all - Requests))
		assertEquals(listOf(Requests, Home, Library), layout.tabs(all))
	}

	@Test
	fun aDestinationBecomingAvailablePastTheMaximumGivesWayBeforeSearch() {
		val layout = TabLayout(
			order = listOf(Home, Library, Requests, LiveTv, Watchlist),
			shown = setOf(Library, Requests, LiveTv, Watchlist, Search),
		)

		assertEquals(listOf(Home, Library, LiveTv, Watchlist, Search), layout.tabs(all - Requests))
		assertEquals(listOf(Home, Library, Requests, LiveTv, Search), layout.tabs(all))
	}

	@Test
	fun aSwitchChangesOnlyWithRoomAndNeverForHomeOrAnUnavailableDestination() {
		val built = TabLayout.available(seerrEnabled = true)
		val roomy = TabLayout(Destination.entries, setOf(Home, Library, Watchlist, Search))

		assertFalse(roomy.canSwitch(Home, built))
		assertFalse(roomy.canSwitch(LiveTv, built))
		assertTrue(roomy.canSwitch(Requests, built))
		assertEquals(listOf(Home, Library, Watchlist, Requests, Search), roomy.withTab(Requests, true, built).tabs(built))
		// Five tabs fill the bar: an off one cannot come on, an on one can still go off.
		val full = roomy.withTab(Requests, true, built)
		assertFalse(full.canSwitch(LiveTv, all))
		assertTrue(full.canSwitch(Library, all))
		assertEquals(listOf(Home, Watchlist, Requests, Search), full.withTab(Library, false, built).tabs(built))
	}

	@Test
	fun aReorderKeepsSearchLastAndRequestsOnlyWhenSeerrIsOn() {
		val moved = TabLayout.standard.reordered(listOf(Search, Watchlist, Home, Library))

		assertEquals(listOf(Watchlist, Home, Library), moved.order.take(3))
		assertEquals(Search, moved.order.last())
		assertEquals(setOf(Home, Library, Watchlist, Search), TabLayout.available(seerrEnabled = false))
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

package com.nphil.marquee.core

import com.nphil.marquee.core.Destination.Home
import com.nphil.marquee.core.Destination.Library
import com.nphil.marquee.core.Destination.LiveTv
import com.nphil.marquee.core.Destination.Requests
import com.nphil.marquee.core.Destination.Search
import com.nphil.marquee.core.Destination.Watchlist
import org.junit.Assert.assertEquals
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
}

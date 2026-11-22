package com.nphil.marquee.ui.library

import com.nphil.marquee.core.Destination
import com.nphil.marquee.ui.shell.Places
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlacesTest {
	@Test
	fun theTitleMenuListsLiveTvWatchlistAndDownloadsInThatOrderAndOnlyThoseThatAreNotTabs() {
		val everything = Places(listOf(Destination.Downloads, Destination.Search, Destination.Requests, Destination.Watchlist, Destination.LiveTv)) {}
		assertEquals(listOf(Destination.LiveTv, Destination.Watchlist, Destination.Downloads), everything.inLibraryMenu())

		val some = Places(listOf(Destination.Watchlist, Destination.Search, Destination.Requests, Destination.Library)) {}
		assertEquals(listOf(Destination.Watchlist), some.inLibraryMenu())

		assertEquals(emptyList<Destination>(), Places.None.inLibraryMenu())
	}
}

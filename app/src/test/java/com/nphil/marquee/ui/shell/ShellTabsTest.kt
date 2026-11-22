package com.nphil.marquee.ui.shell

import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTabsTest {
	private val withSeerr = TabLayout.available(true)
	private val withoutSeerr = TabLayout.available(false)

	@Test
	fun aSelectedTabThatLeftTheBarFallsBackToHome() {
		val tabs = listOf(Destination.Home, Destination.Library, Destination.Search)
		assertEquals("Library", selectedPage("Library", tabs))
		assertEquals("Home", selectedPage("Watchlist", tabs))
		assertEquals("Home", selectedPage("Gone", tabs))
		assertEquals(SETTINGS_KEY, selectedPage(SETTINGS_KEY, tabs))
	}

	@Test
	fun placesAreTheAvailableDestinationsOutsideTheTabsInLayoutOrder() {
		val layout = TabLayout(
			order = listOf(Destination.Home, Destination.Watchlist, Destination.Requests, Destination.Library, Destination.LiveTv),
			shown = setOf(Destination.Home, Destination.Library),
		)
		val tabs = layout.tabs(withSeerr)
		assertEquals(listOf(Destination.Home, Destination.Library), tabs)
		assertEquals(
			listOf(Destination.Watchlist, Destination.Requests, Destination.LiveTv, Destination.Downloads, Destination.Search),
			placesOutsideTabs(layout, tabs, withSeerr),
		)
		assertEquals(
			listOf(Destination.Watchlist, Destination.LiveTv, Destination.Downloads, Destination.Search),
			placesOutsideTabs(layout, layout.tabs(withoutSeerr), withoutSeerr),
		)
	}

	@Test
	fun anOpenedPlaceIsDroppedOnceItIsNoLongerOne() {
		val others = listOf(Destination.Watchlist, Destination.Requests)
		assertEquals(Destination.Requests, openedPlace("Requests", others))
		assertEquals(null, openedPlace("Search", others))
		assertEquals(null, openedPlace(null, others))
	}

	@Test
	fun openingATabSwitchesToItAndAnUnavailableOneDoesNothing() {
		val tabs = listOf(Destination.Home, Destination.Search)
		assertEquals(PlaceRoute.Switch(Destination.Search), routePlace(Destination.Search, tabs, withoutSeerr))
		assertEquals(PlaceRoute.Open(Destination.Watchlist), routePlace(Destination.Watchlist, tabs, withoutSeerr))
		assertEquals(PlaceRoute.Ignore, routePlace(Destination.Requests, tabs, withoutSeerr))
	}

	@Test
	fun homeOffersItsPlacesOnlyWhileLibraryIsNotATab() {
		val noLibraryTab = Places(listOf(Destination.Library, Destination.Watchlist)) {}
		assertEquals(listOf(Destination.Library, Destination.Watchlist), homeMenu(noLibraryTab))
		assertEquals(emptyList<Destination>(), homeMenu(Places(listOf(Destination.Watchlist)) {}))
		assertEquals(emptyList<Destination>(), homeMenu(Places.None))
	}
}

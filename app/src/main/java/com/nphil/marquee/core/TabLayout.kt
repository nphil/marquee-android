package com.nphil.marquee.core

import androidx.compose.runtime.Immutable

/** A top-level place in the app (the iPhone's DESIGN §7). Each can be a tab; one that is not keeps a way in from another page. */
enum class Destination { Home, Library, LiveTv, Watchlist, Search, Requests, Downloads }

/**
 * The navigation as the iPhone app's TabLayout rules it: every destination in an order, and which are tabs. Home is
 * always a tab, since its profile button opens Settings. Search stays last. At most [MAXIMUM_TABS] tabs: past that,
 * the last before Search give way and keep their other way in.
 */
@Immutable
class TabLayout(order: List<Destination>, shown: Set<Destination>) {
	/** Every destination once, Search last. */
	val order: List<Destination> = (order + Destination.entries).distinct().filter { it != Destination.Search } + Destination.Search

	/** The destinations switched on; always holds Home. */
	val shown: Set<Destination> = shown + Destination.Home

	/** The tabs in order: Home, and each destination switched on that is [available]. */
	fun tabs(available: Set<Destination>): List<Destination> {
		val tabs = order.filterTo(ArrayList()) { it == Destination.Home || (it in shown && it in available) }
		while (tabs.size > MAXIMUM_TABS) {
			val last = tabs.indexOfLast { it != Destination.Home && it != Destination.Search }
			if (last < 0) break
			tabs.removeAt(last)
		}
		return tabs
	}

	companion object {
		const val MAXIMUM_TABS = 5

		/**
		 * What the app opens with: the iPhone's tabs, with Requests too, since a sidebar has the room. The destinations
		 * not built yet (Live TV, Downloads) are left out by availability.
		 */
		val standard = TabLayout(
			order = Destination.entries,
			shown = setOf(Destination.Home, Destination.Library, Destination.LiveTv, Destination.Watchlist, Destination.Requests, Destination.Search),
		)
	}
}

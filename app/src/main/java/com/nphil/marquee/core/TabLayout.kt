package com.nphil.marquee.core

import androidx.compose.runtime.Immutable

/** A top-level place in the app (the iPhone's DESIGN §7). Each can be a tab; one that is not keeps a way in from another page. */
enum class Destination { Home, Library, LiveTv, Watchlist, Search, Requests, Downloads }

/** What the phone's tab bar does while a page scrolls. The tablet's sidebar stays put. */
enum class TabBarBehavior { Always, HideOnScroll }

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

	/** The destinations switched on, by name in this layout's order, as [TabLayout.saved] reads them. */
	val savedShown: List<String> get() = order.filter { it in shown }.map { it.name }

	/** The destinations in order, by name. */
	val savedOrder: List<String> get() = order.map { it.name }

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

	/** Whether the switch for [destination] can change: never Home's or an unavailable one's, and an off one only while the bar has room. */
	fun canSwitch(destination: Destination, available: Set<Destination>): Boolean {
		if (destination == Destination.Home || destination !in available) return false
		val tabs = tabs(available)
		return destination in tabs || tabs.size < MAXIMUM_TABS
	}

	/**
	 * This layout with [destination] switched on or off as a tab, as far as [canSwitch] allows. The edit starts from
	 * the bar on screen, so a destination switched on while unavailable or crowded out does not return unseen.
	 */
	fun withTab(destination: Destination, on: Boolean, available: Set<Destination>): TabLayout {
		if (!canSwitch(destination, available)) return this
		val shown = tabs(available).toMutableSet()
		if (on) shown.add(destination) else shown.remove(destination)
		return TabLayout(order, shown)
	}

	/** This layout with [order], its order rearranged, as the new order; Search stays last. */
	fun reordered(order: List<Destination>): TabLayout = TabLayout(order, shown)

	override fun equals(other: Any?): Boolean = other is TabLayout && order == other.order && shown == other.shown

	override fun hashCode(): Int = 31 * order.hashCode() + shown.hashCode()

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

		/** A layout saved as destination names; names this version does not know, such as one a later version saved, are dropped. */
		fun saved(order: List<String>, shown: List<String>): TabLayout {
			fun named(names: List<String>) = names.mapNotNull { name -> Destination.entries.firstOrNull { it.name == name } }
			return TabLayout(named(order), named(shown).toSet())
		}

		/** The destinations that can be tabs now: Requests only once Seerr is on; Live TV and Downloads are not built yet. */
		fun available(seerrEnabled: Boolean): Set<Destination> = buildSet {
			add(Destination.Home)
			add(Destination.Library)
			add(Destination.Watchlist)
			add(Destination.Search)
			if (seerrEnabled) add(Destination.Requests)
		}
	}
}

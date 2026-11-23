package com.nphil.marquee.core

import androidx.compose.runtime.Immutable

/** A top-level place in the app (the iPhone's DESIGN §7). Each can be a tab; one that is not keeps a way in from another page. */
enum class Destination { Home, Library, LiveTv, Watchlist, Search, Requests, Downloads }

/** What the phone's tab bar does while a page scrolls. The tablet's sidebar stays put. */
enum class TabBarBehavior { Always, HideOnScroll }

/**
 * The navigation as the iPhone app's TabLayout rules it: every destination in an order, and which are tabs. Home is
 * always a tab, since its profile button opens Settings. Search stays last. A phone's bar holds as many tabs as its
 * width allows ([phoneLimit]) and the tablet's sidebar has no limit. Past a limit, the last before Search give way
 * and keep their other way in.
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

	/** The tabs in order: Home, and each destination switched on that is [available], at most [limit] of them. */
	fun tabs(available: Set<Destination>, limit: Int = NO_LIMIT): List<Destination> {
		val tabs = order.filterTo(ArrayList()) { it == Destination.Home || (it in shown && it in available) }
		while (tabs.size > limit) {
			val last = tabs.indexOfLast { it != Destination.Home && it != Destination.Search }
			if (last < 0) break
			tabs.removeAt(last)
		}
		return tabs
	}

	/** Whether the switch for [destination] can change: never Home's or an unavailable one's, and an off one only while the bar has room for [limit] tabs. */
	fun canSwitch(destination: Destination, available: Set<Destination>, limit: Int = NO_LIMIT): Boolean {
		if (destination == Destination.Home || destination !in available) return false
		val tabs = tabs(available, limit)
		return destination in tabs || tabs.size < limit
	}

	/**
	 * This layout with [destination] switched on or off as a tab, as far as [canSwitch] allows. The edit starts from
	 * the bar on screen, so a destination switched on while unavailable or crowded out does not return unseen.
	 */
	fun withTab(destination: Destination, on: Boolean, available: Set<Destination>, limit: Int = NO_LIMIT): TabLayout {
		if (!canSwitch(destination, available, limit)) return this
		val shown = tabs(available, limit).toMutableSet()
		if (on) shown.add(destination) else shown.remove(destination)
		return TabLayout(order, shown)
	}

	/** This layout with [order], its order rearranged, as the new order; Search stays last. */
	fun reordered(order: List<Destination>): TabLayout = TabLayout(order, shown)

	override fun equals(other: Any?): Boolean = other is TabLayout && order == other.order && shown == other.shown

	override fun hashCode(): Int = 31 * order.hashCode() + shown.hashCode()

	companion object {
		/** For the sidebar, which shows every tab. */
		const val NO_LIMIT = Int.MAX_VALUE

		private const val TAB_WIDTH_DP = 72f
		private const val FEWEST_TABS = 3
		private const val MOST_TABS = 5

		/** How many tabs a phone's bar holds in a window [widthDp] wide: one for each 72 dp, from three to the iPhone's five. */
		fun phoneLimit(widthDp: Float): Int = (widthDp / TAB_WIDTH_DP).toInt().coerceIn(FEWEST_TABS, MOST_TABS)

		/** Default tabs; on a phone Requests stays in Library's Places when the bar is full. */
		val standard = TabLayout(
			order = Destination.entries,
			shown = setOf(Destination.Home, Destination.Library, Destination.LiveTv, Destination.Watchlist, Destination.Requests, Destination.Search),
		)

		/** A layout saved as destination names; names this version does not know, such as one a later version saved, are dropped. */
		fun saved(order: List<String>, shown: List<String>): TabLayout {
			fun named(names: List<String>) = names.mapNotNull { name -> Destination.entries.firstOrNull { it.name == name } }
			return TabLayout(named(order), named(shown).toSet())
		}

		/** Requests is available only once Seerr is on. */
		fun available(seerrEnabled: Boolean): Set<Destination> = buildSet {
			add(Destination.Home)
			add(Destination.Library)
			add(Destination.LiveTv)
			add(Destination.Watchlist)
			add(Destination.Search)
			add(Destination.Downloads)
			if (seerrEnabled) add(Destination.Requests)
		}
	}
}

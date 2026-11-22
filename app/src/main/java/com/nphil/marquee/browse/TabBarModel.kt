package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.TabBarBehavior
import com.nphil.marquee.core.TabLayout
import com.nphil.marquee.data.BrowsePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The destinations the Tab Bar page lists, Search apart: the ones the app has, whether or not Seerr is on. */
private val listed: Set<Destination> = TabLayout.available(seerrEnabled = true) - Destination.Search

/** The destinations whose switch is a movable row, in the layout's order. */
fun TabLayout.movable(): List<Destination> = order.filter { it in listed }

/**
 * This layout with the movable row at [from] put where [to] is. The destinations the page does not list keep their
 * places among the others, so Home stays a tab and Search stays last as [TabLayout.reordered] has it.
 */
fun TabLayout.movedRow(from: Int, to: Int): TabLayout {
	val rows = movable()
	if (from !in rows.indices || to !in rows.indices || from == to) return this
	val moved = rows.toMutableList().also { it.add(to, it.removeAt(from)) }
	val slots = moved.iterator()
	return reordered(order.map { if (it in listed) slots.next() else it })
}

/** Where [destination] opens from while it is not a tab (DESIGN §7), or what it needs while it is [available] no longer. */
fun tabNote(destination: Destination, available: Boolean): String {
	if (!available) return "Needs Seerr, set up in Settings."
	return when (destination) {
		Destination.Home -> "Always a tab."
		Destination.Library -> "Otherwise in Home's title menu."
		Destination.LiveTv -> "Otherwise in the Library's title menu."
		Destination.Watchlist -> "Otherwise a row on Home and in the Library's title menu."
		Destination.Search -> "Otherwise a button at the top of the Library."
		Destination.Requests -> "Otherwise the + button at the top of the Library."
		Destination.Downloads -> "Otherwise in the Library's title menu and Settings."
	}
}

/** What the Tab Bar page draws: the layout, what the phone's bar does on scroll, and the destinations that can be tabs now. */
@Immutable
data class TabBarUi(val layout: TabLayout, val behavior: TabBarBehavior, val available: Set<Destination>) {
	val tabs: List<Destination> get() = layout.tabs(available)
}

/** Settings › Tab Bar: each change is saved at once, and the shell follows it. */
class TabBarModel(
	private val preferences: BrowsePreferences,
	seerrEnabled: Flow<Boolean>,
) : ViewModel() {
	/** Null until the saved layout is read. */
	val ui: StateFlow<TabBarUi?> = combine(preferences.tabLayout, preferences.tabBarBehavior, seerrEnabled) { layout, behavior, seerr ->
		TabBarUi(layout, behavior, TabLayout.available(seerr))
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	fun setTab(destination: Destination, on: Boolean) = edit { it.layout.withTab(destination, on, it.available) }

	fun move(from: Int, to: Int) = edit { it.layout.movedRow(from, to) }

	fun setBehavior(behavior: TabBarBehavior) {
		viewModelScope.launch { preferences.setTabBarBehavior(behavior) }
	}

	private fun edit(change: (TabBarUi) -> TabLayout) {
		val current = ui.value ?: return
		val edited = change(current)
		if (edited == current.layout) return
		viewModelScope.launch { preferences.setTabLayout(edited) }
	}
}

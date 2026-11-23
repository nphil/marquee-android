package com.nphil.marquee.ui.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.nphil.marquee.R
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.TabLayout

/** The saved key of the Settings page: it is shown like a tab but is not a [Destination]. */
internal const val SETTINGS_KEY = "Settings"

/** Windows at least this wide (dp) get the sidebar; narrower ones keep the bottom bar. */
internal const val WIDE_WIDTH_DP = 600

/** How many tabs a window [widthDp] wide shows: every one in the sidebar, as many as fit in a phone's bar. */
internal fun tabLimit(widthDp: Float): Int = if (widthDp >= WIDE_WIDTH_DP) TabLayout.NO_LIMIT else TabLayout.phoneLimit(widthDp)

/** The page to show for the [saved] key: Settings, or a tab still in the bar; Home once the layout edit removed it. */
internal fun selectedPage(saved: String, tabs: List<Destination>): String =
	if (saved == SETTINGS_KEY || tabs.any { it.name == saved }) saved else Destination.Home.name

/** The destinations that are available but not tabs, in the layout's order: what [Places.open] can open. */
internal fun placesOutsideTabs(layout: TabLayout, tabs: List<Destination>, available: Set<Destination>): List<Destination> =
	layout.order.filter { it !in tabs && it in available }

/** The place opened over the page, from its [saved] name; none once it is no longer a place (it became a tab or went away). */
internal fun openedPlace(saved: String?, others: List<Destination>): Destination? = others.firstOrNull { it.name == saved }

/** What opening a destination does. */
internal sealed interface PlaceRoute {
	/** It is a tab: go to the tab. */
	data class Switch(val destination: Destination) : PlaceRoute

	/** It is a place: show it over the page. */
	data class Open(val destination: Destination) : PlaceRoute

	/** It is not available (Requests while Seerr is off): nothing to open. */
	data object Ignore : PlaceRoute
}

internal fun routePlace(destination: Destination, tabs: List<Destination>, available: Set<Destination>): PlaceRoute = when {
	destination in tabs -> PlaceRoute.Switch(destination)
	destination in available -> PlaceRoute.Open(destination)
	else -> PlaceRoute.Ignore
}

/** Home's title menu: every place that is not a tab, and only while Library is not a tab either (DESIGN §7). */
internal fun homeMenu(places: Places): List<Destination> = if (Destination.Library in places.others) places.others else emptyList()

/** The icon of a tab: the filled one while [selected], for the destinations that have one. */
internal fun Destination.tabIcon(selected: Boolean): Int = when (this) {
	Destination.Home -> if (selected) R.drawable.ic_home_filled else R.drawable.ic_home
	Destination.Library -> if (selected) R.drawable.ic_video_library_filled else R.drawable.ic_video_library
	Destination.Watchlist -> if (selected) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
	Destination.Requests -> R.drawable.ic_playlist_add
	else -> icon
}

/** How far the bar is hidden (0 shown, [limit] gone) after a page scrolled by [consumedY]; negative is the page moving down. */
internal fun barHidden(current: Float, consumedY: Float, limit: Float): Float = (current - consumedY).coerceIn(0f, maxOf(limit, 0f))

/** Where a half-hidden bar comes to rest when the scroll ends: the nearer end. */
internal fun barSettled(hidden: Float, limit: Float): Float = if (limit > 0f && hidden > limit / 2f) limit else 0f

/**
 * Slides the phone's bottom bar away while a page scrolls down and back while it scrolls up. It reads what the page
 * actually consumed, so a page that cannot scroll leaves the bar alone. [hidden] is read in layout only.
 */
internal class BarScroll(private val settle: AnimationSpec<Float>) : NestedScrollConnection {
	var hidden by mutableFloatStateOf(0f)
		private set

	/** The bar's full height; set by its layout. */
	var limit = 0f

	/** False for the fixed bar, which this then never moves. */
	var enabled = false

	private var gesture = 0

	fun show() {
		gesture++
		hidden = 0f
	}

	override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
		if (enabled && consumed.y != 0f) {
			gesture++
			hidden = barHidden(hidden, consumed.y, limit)
		}
		return Offset.Zero
	}

	override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
		val start = gesture
		val target = barSettled(hidden, limit)
		if (enabled && target != hidden) {
			try {
				animate(hidden, target, animationSpec = settle) { value, _ ->
					// A new scroll takes the bar over from the animation.
					if (gesture != start) throw Interrupted()
					hidden = value
				}
			} catch (_: Interrupted) {
				// The new scroll owns the bar now.
			}
		}
		return Velocity.Zero
	}

	private class Interrupted : RuntimeException()
}

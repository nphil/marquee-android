package com.nphil.marquee.ui.shell

import androidx.compose.runtime.Immutable
import com.nphil.marquee.R
import com.nphil.marquee.core.Destination

/**
 * The destinations that are not tabs, and the way to open one. A place that is not a tab keeps a way in from another
 * page (the iPhone's DESIGN §7): Home's title menu while Library is not a tab, Library's title menu for Live TV,
 * Watchlist and Downloads, a Search button and a Requests + button at the top of Library.
 */
@Immutable
class Places(
	/** The destinations that are available but not tabs, in the tab layout's order. */
	val others: List<Destination>,
	/** Opens [Destination] over the page on screen; Back returns. */
	val open: (Destination) -> Unit,
) {
	fun isTab(destination: Destination): Boolean = destination !in others

	companion object {
		/** Nothing to open: a page shown on its own, as in a screenshot. */
		val None = Places(emptyList()) {}
	}
}

/** What a place is called in a menu. */
val Destination.title: String
	get() = when (this) {
		Destination.Home -> "Home"
		Destination.Library -> "Library"
		Destination.LiveTv -> "Live TV"
		Destination.Watchlist -> "Watchlist"
		Destination.Search -> "Search"
		Destination.Requests -> "Requests"
		Destination.Downloads -> "Downloads"
	}

/** The icon of a place in a menu and the tab bar; the filled one is for the selected tab. */
val Destination.icon: Int
	get() = when (this) {
		Destination.Home -> R.drawable.ic_home
		Destination.Library -> R.drawable.ic_video_library
		Destination.LiveTv -> R.drawable.ic_tv
		Destination.Watchlist -> R.drawable.ic_bookmark
		Destination.Search -> R.drawable.ic_search
		Destination.Requests -> R.drawable.ic_add
		Destination.Downloads -> R.drawable.ic_download
	}

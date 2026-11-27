package com.nphil.marquee.bigscreen

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import java.text.Collator

internal enum class TvWatchlistSort(val label: String, val first: String, val reverse: String) {
	Added("Date added", "Newest first", "Oldest first"), Title("Title", "A–Z", "Z–A"),
	Release("Release date", "Newest first", "Oldest first"), Rating("Rating", "Highest first", "Lowest first"),
}

internal data class TvWatchlistOrder(val sort: TvWatchlistSort = TvWatchlistSort.Added, val reversed: Boolean = false, val type: MediaType? = null) {
	val typeLabel get() = when (type) { MediaType.Movie -> "Movies"; MediaType.Show -> "TV Shows"; else -> "All titles" }
	val direction get() = if (reversed) sort.reverse else sort.first
	val needsWholeList get() = sort != TvWatchlistSort.Added || reversed || type != null

	fun apply(items: List<PlexItem>): List<PlexItem> {
		val rows = if (type == null) items else items.filter { it.type == type }
		return when (sort) {
			TvWatchlistSort.Added -> if (reversed) rows.asReversed() else rows
			TvWatchlistSort.Title -> {
				val collator = Collator.getInstance()
				val comparator = compareBy<PlexItem, String>(collator) { it.titleSort?.takeIf(String::isNotBlank) ?: it.title }
				rows.sortedWith(if (reversed) comparator.reversed() else comparator)
			}
			TvWatchlistSort.Release -> rows.sortedWith(byPresent { it.originallyAvailableAt?.takeIf(String::isNotBlank) })
			TvWatchlistSort.Rating -> rows.sortedWith(byPresent { it.rating ?: it.audienceRating })
		}
	}

	private fun <T : Comparable<T>> byPresent(value: (PlexItem) -> T?): Comparator<PlexItem> = Comparator { a, b ->
		val x = value(a); val y = value(b)
		when { x == null -> if (y == null) 0 else 1; y == null -> -1; reversed -> x.compareTo(y); else -> y.compareTo(x) }
	}
}

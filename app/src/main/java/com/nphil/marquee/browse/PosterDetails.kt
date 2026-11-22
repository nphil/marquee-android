package com.nphil.marquee.browse

import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.core.minutesLeft
import com.nphil.marquee.core.videoMarks
import com.nphil.marquee.data.PosterLabels
import com.nphil.marquee.data.PosterLabels.Details
import java.time.LocalDate

internal fun PosterLabels.reservesLine(sort: Sort?): Boolean =
	details != Details.None || (showsSortValue && sort?.showsValue == true)

internal fun PlexItem.posterLine(labels: PosterLabels, sort: Sort?, clock: DayClock?): SortValue? {
	if (labels.showsSortValue && sort?.showsValue == true) sortValue(sort, clock ?: DayClock.now())?.let { return it }
	return posterDetails(labels.details, clock)?.let { SortValue(it) }
}

private fun PlexItem.posterDetails(details: Details, clock: DayClock?): String? {
	if (details == Details.None) return null
	if (type == MediaType.Collection) return childCount?.let { count(it, "title") }
	if (type != MediaType.Movie && type != MediaType.Show) return null
	minutesLeft?.let { return ItemText.left(it) }
	val release = originallyAvailableAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
	if (release != null) {
		val day = clock ?: DayClock.now()
		if (release > day.today) return day.day(release)
	}
	return when (details) {
		Details.None -> null
		Details.YearAndLength -> if (type == MediaType.Show) {
			val unwatched = unwatchedLeafCount ?: 0
			if (unwatched > 0 && (viewedLeafCount ?: 0) > 0) count(unwatched, "new episode")
			else joined(year?.toString(), childCount?.let { count(it, "season") })
		} else joined(year?.toString(), ItemText.runtime(duration))
		Details.Genre -> genres.firstOrNull { it.isNotBlank() }
		Details.Video -> if (type == MediaType.Movie && media.isNotEmpty()) media[0].videoMarks else year?.toString()
		Details.RatingAndScore -> joined(contentRating, (audienceRating ?: rating)?.let(ItemText::score))
	}
}

private fun count(value: Int, name: String) = "$value $name" + if (value == 1) "" else "s"

private fun joined(first: String?, second: String?): String? = when {
	first.isNullOrBlank() -> second?.takeUnless(String::isBlank)
	second.isNullOrBlank() -> first
	else -> "$first · $second"
}

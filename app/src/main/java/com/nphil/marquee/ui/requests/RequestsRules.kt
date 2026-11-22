package com.nphil.marquee.ui.requests

import androidx.navigation3.runtime.NavKey
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrCatalogue
import com.nphil.marquee.core.SeerrDetails
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrNamed
import com.nphil.marquee.core.SeerrRequest
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date

/** Where Requests' own back stack can be. Each is saved with the stack. */
@Serializable
sealed interface RequestsRoute : NavKey

/** Discover, My Requests and search. */
@Serializable
data object RequestsHome : RequestsRoute

/** A list to page through: the trending feed or a query with filters. */
@Serializable
data class RequestsBrowse(val feed: SeerrFeed) : RequestsRoute

@Serializable
data class RequestsTitle(val kind: SeerrKind, val tmdbId: Int) : RequestsRoute

/** The rules of the request sheet and of the filter chips, as functions so they can be checked without a screen. */
internal object RequestsRules {
	/** The request sheet starts on 4K only when 4K is the one thing that can be asked. */
	fun starts4K(details: SeerrDetails): Boolean = details.canRequest4K && !details.canRequest

	/** A series starts with every season that can still be requested chosen. */
	fun startingSeasons(details: SeerrDetails, is4K: Boolean): Set<Int> =
		details.seasonChoices(is4K).filter { it.requestable }.mapTo(LinkedHashSet()) { it.number }

	/** Send needs a quality that can be requested, and for a series with partial requests, a season chosen. */
	fun canSend(details: SeerrDetails, is4K: Boolean, chosen: Set<Int>): Boolean =
		details.canRequest(is4K) &&
			(details.title.kind == SeerrKind.Movie || !details.partialRequests || chosen.isNotEmpty())

	/** The region the streaming filter asks for: the filter's own, else the one picked, else the device's if TMDB lists it, else US. */
	fun effectiveRegion(query: SeerrBrowseQuery, picked: String?, deviceRegion: String, regions: List<SeerrNamed>): String =
		query.streaming?.region
			?: picked
			?: deviceRegion.takeIf { code -> regions.any { it.code == code } }
			?: "US"

	/** The chips of the filters that are on, in the order of the View menu. */
	fun chips(query: SeerrBrowseQuery, catalogue: SeerrCatalogue, languages: List<SeerrNamed>, providerName: String?): List<Chip> =
		buildList {
			if (query.upcoming) add(Chip(Filter.Upcoming, "Upcoming"))
			query.era?.let { id -> add(Chip(Filter.Era, catalogue.eras.firstOrNull { it.id == id }?.title ?: id)) }
			query.language?.let { code -> add(Chip(Filter.Language, languages.firstOrNull { it.code == code }?.name ?: code)) }
			query.minRating?.let { add(Chip(Filter.Rating, "Rating $it+")) }
			query.runtime?.let { id ->
				add(Chip(Filter.Runtime, catalogue.runtimes.firstOrNull { it.id == id }?.title(query.kind) ?: id))
			}
			query.streaming?.let { add(Chip(Filter.Streaming, "${providerName ?: "Streaming"} · ${it.region}")) }
		}

	/** The query with one filter off. */
	fun without(query: SeerrBrowseQuery, filter: Filter): SeerrBrowseQuery = when (filter) {
		Filter.Upcoming -> query.copy(upcoming = false)
		Filter.Era -> query.copy(era = null)
		Filter.Language -> query.copy(language = null)
		Filter.Rating -> query.copy(minRating = null)
		Filter.Runtime -> query.copy(runtime = null)
		Filter.Streaming -> query.copy(streaming = null)
	}

	/** A request's "Seasons 1, 2 · 4K" line; a movie has only the 4K part. */
	fun requestLine(request: SeerrRequest): String? {
		val seasons = request.seasons.sorted().takeIf { it.isNotEmpty() }?.let { (if (it.size == 1) "Season " else "Seasons ") + it.joinToString(", ") }
		return listOfNotNull(seasons, "4K".takeIf { request.is4K }).joinToString(" · ").ifEmpty { null }
	}

	/** A request's date in the device's short format, or null when Seerr gave none. */
	fun requestDate(request: SeerrRequest, format: DateFormat): String? =
		request.createdAt?.let { format.format(Date((it * 1000).toLong())) }

	/** The title page's "year · N min · genres" line, leaving out what Seerr did not give. */
	fun metaLine(details: SeerrDetails): String = listOfNotNull(
		details.title.year?.toString(),
		details.runtime?.takeIf { it > 0 }?.let { "$it min" },
		details.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
	).joinToString(" · ")
}

enum class Filter { Upcoming, Era, Language, Rating, Runtime, Streaming }

data class Chip(val filter: Filter, val text: String)

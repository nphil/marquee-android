package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What the Seerr calls (bridge/Sources/MarqueeBridge/SeerrWire.swift) send and take. Core decides the badges, the
// seasons a title can be requested for and whether this account may request it; these types carry its answers.

@Serializable
enum class SeerrKind {
	@SerialName("movie")
	Movie,

	@SerialName("tv")
	Tv,
}

/** A status Core words and Kotlin draws: [name] is the case (`available`, `partiallyAvailable`, ...), [title] its label. */
@Serializable
data class SeerrBadge(val name: String, val title: String)

@Serializable
data class SeerrTitle(
	/** `movie/603`: unique across kinds, the key of a list. */
	val id: String,
	val kind: SeerrKind,
	@SerialName("tmdbID") val tmdbId: Int,
	val name: String,
	val year: Int? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val overview: String = "",
	val badge: SeerrBadge? = null,
)

@Serializable
data class SeerrPage(val titles: List<SeerrTitle>, val page: Int, val totalPages: Int) {
	val hasMore: Boolean get() = page < totalPages
}

@Serializable
data class SeerrUser(val id: Int, val name: String)

@Serializable
data class SeerrServerSettings(
	val movie4KEnabled: Boolean = false,
	val series4KEnabled: Boolean = false,
	val partialRequestsEnabled: Boolean = true,
	val specialEpisodesEnabled: Boolean = false,
)

@Serializable
data class SeerrGenre(val id: Int, val name: String, val backdropPath: String? = null)

/** A language or a streaming region: Seerr's code and its name. */
@Serializable
data class SeerrNamed(val code: String, val name: String)

@Serializable
data class SeerrProvider(val id: Int, val name: String)

@Serializable
data class SeerrSeason(
	val number: Int,
	val name: String,
	val episodeCount: Int,
	/** Why the season cannot be requested now; null when it can. */
	val badge: SeerrBadge? = null,
) {
	val requestable: Boolean get() = badge == null
}

/** A request on My Requests, with its title when Seerr knows it. */
@Serializable
data class SeerrRequest(
	val id: Int,
	val kind: SeerrKind,
	@SerialName("tmdbID") val tmdbId: Int? = null,
	val is4K: Boolean = false,
	val seasons: List<Int> = emptyList(),
	val requesterName: String? = null,
	/** Seconds since 1970. */
	val createdAt: Double? = null,
	val badge: SeerrBadge? = null,
	val title: SeerrTitle? = null,
)

/** One quality's standing on the title page: the badge and, while a request is open, who made it. */
@Serializable
data class SeerrStatus(val badge: SeerrBadge, val requestedBy: String? = null)

@Serializable
data class SeerrPlexItem(val ratingKey: String)

/** A title page, with the account's rules already applied. */
@Serializable
data class SeerrDetails(
	val title: SeerrTitle,
	val tagline: String? = null,
	val genres: List<String> = emptyList(),
	val runtime: Int? = null,
	val seasons: List<SeerrSeason> = emptyList(),
	val status: SeerrStatus? = null,
	val status4K: SeerrStatus? = null,
	val canRequest: Boolean = false,
	val canRequest4K: Boolean = false,
	val seasonChoices: List<SeerrSeason> = emptyList(),
	val seasonChoices4K: List<SeerrSeason> = emptyList(),
	val partialRequests: Boolean = true,
	val watchlistAddRequests: Boolean = false,
	/** The title on the signed-in Plex server, when Seerr links it there. */
	val plexItem: SeerrPlexItem? = null,
) {
	/** Whether either quality can be requested: the Request button. */
	val canRequestAny: Boolean get() = canRequest || canRequest4K

	fun seasonChoices(is4K: Boolean): List<SeerrSeason> = if (is4K) seasonChoices4K else seasonChoices
	fun canRequest(is4K: Boolean): Boolean = if (is4K) canRequest4K else canRequest
}

@Serializable
data class SeerrStreaming(val provider: Int, val region: String)

/** What a browse screen lists, and the state of its filters. Sorts, eras and runtimes are named by [SeerrCatalogue]. */
@Serializable
data class SeerrBrowseQuery(
	val kind: SeerrKind,
	val genre: Int? = null,
	val sort: String = "popularity",
	val reversed: Boolean = false,
	val upcoming: Boolean = false,
	val era: String? = null,
	val language: String? = null,
	val minRating: Int? = null,
	val runtime: String? = null,
	val streaming: SeerrStreaming? = null,
) {
	/** Whether any filter of the View menu is on; the sort and the genre are not. */
	val hasMenuFilters: Boolean
		get() = upcoming || era != null || language != null || minRating != null || runtime != null || streaming != null

	/** The query with the View menu's filters off, keeping the kind, the genre, the sort and its order. */
	fun clearedFilters() = copy(upcoming = false, era = null, language = null, minRating = null, runtime = null, streaming = null)
}

/** A list of titles to page through: the trending feed, or a browse query. */
@Serializable
data class SeerrFeed(val trending: Boolean? = null, val query: SeerrBrowseQuery? = null)

@Serializable
data class SeerrCatalogue(
	val rows: List<Row>,
	val sorts: List<Sort>,
	val eras: List<Era>,
	val runtimes: List<Runtime>,
) {
	/** A Discover row: a shelf of titles, or genre cards for one kind. */
	@Serializable
	data class Row(
		val id: String,
		val title: String,
		val shelf: String? = null,
		val genres: SeerrKind? = null,
		/** What its chevron opens. */
		val feed: SeerrFeed,
	)

	@Serializable
	data class Sort(val id: String, val title: String, val first: String, val reversed: String)

	@Serializable
	data class Era(val id: String, val title: String)

	@Serializable
	data class Runtime(val id: String, val movie: String, val tv: String) {
		fun title(kind: SeerrKind) = if (kind == SeerrKind.Movie) movie else tv
	}
}

/** The answer of `seerr.parse`: [problem] is `scheme`, `host`, `plaintext` or `key`, or null for an address Seerr can be asked at. */
@Serializable
data class SeerrParsed(val problem: String? = null, val address: String? = null)

/** A bridge answer: [session] is a sign-in cookie to keep, present only when a Plex sign-in happened during the call. */
@Serializable
data class SeerrReply<T>(val value: T, val session: String? = null)

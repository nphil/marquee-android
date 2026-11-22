package com.nphil.marquee.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The Seerr bridge calls (bridge/Sources/MarqueeBridge/SeerrCalls.swift) as typed suspend calls. Every call names the
// connection it is for; [Seerr] supplies it from the saved settings and keeps the sign-in cookie a call hands back.

/** The connection a call is for. A null [apiKey] signs in with Plex, with [plexToken]. */
class SeerrLink(val address: String, val apiKey: String?, val plexToken: String?, val session: String?) {
	internal fun params(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
		put("address", address)
		if (apiKey != null) put("apiKey", apiKey)
		if (plexToken != null) put("plexToken", plexToken)
		if (session != null) put("session", session)
		extra()
	}
}

/** Checks what was typed on the settings page: Core names what is wrong with it, if anything. */
suspend fun MarqueeCore.seerrParse(address: String, apiKey: String?): SeerrParsed = call(
	"seerr.parse",
	buildJsonObject {
		put("address", address)
		if (apiKey != null) put("apiKey", apiKey)
	},
)

/** The Discover rows in order and the names of the sorts, decades and runtimes. Nothing is asked of Seerr. */
suspend fun MarqueeCore.seerrCatalogue(): SeerrCatalogue = call("seerr.catalogue", JsonObject(emptyMap()))

suspend fun MarqueeCore.seerrVersion(link: SeerrLink): SeerrReply<String> = call("seerr.version", link.params())

suspend fun MarqueeCore.seerrSignIn(link: SeerrLink): SeerrReply<SeerrUser> = call("seerr.signIn", link.params())

suspend fun MarqueeCore.seerrMe(link: SeerrLink): SeerrReply<SeerrUser> = call("seerr.me", link.params())

suspend fun MarqueeCore.seerrServerSettings(link: SeerrLink): SeerrReply<SeerrServerSettings> =
	call("seerr.serverSettings", link.params())

/** One Discover shelf, by [SeerrCatalogue.Row.shelf]. */
suspend fun MarqueeCore.seerrDiscover(link: SeerrLink, shelf: String, page: Int = 1): SeerrReply<List<SeerrTitle>> =
	call("seerr.discover", link.params { put("shelf", shelf); put("page", page) })

suspend fun MarqueeCore.seerrBrowse(link: SeerrLink, feed: SeerrFeed, page: Int): SeerrReply<SeerrPage> = call(
	"seerr.browse",
	link.params {
		put("feed", CoreJson.encodeToJsonElement(SeerrFeed.serializer(), feed))
		put("page", page)
	},
)

suspend fun MarqueeCore.seerrGenres(link: SeerrLink, kind: SeerrKind): SeerrReply<List<SeerrGenre>> =
	call("seerr.genres", link.params { put("kind", kind.wire) })

/** Genres with a backdrop to draw their card from. */
suspend fun MarqueeCore.seerrGenreCards(link: SeerrLink, kind: SeerrKind): SeerrReply<List<SeerrGenre>> =
	call("seerr.genreCards", link.params { put("kind", kind.wire) })

suspend fun MarqueeCore.seerrLanguages(link: SeerrLink): SeerrReply<List<SeerrNamed>> = call("seerr.languages", link.params())

suspend fun MarqueeCore.seerrWatchRegions(link: SeerrLink): SeerrReply<List<SeerrNamed>> = call("seerr.watchRegions", link.params())

suspend fun MarqueeCore.seerrWatchProviders(link: SeerrLink, kind: SeerrKind, region: String): SeerrReply<List<SeerrProvider>> =
	call("seerr.watchProviders", link.params { put("kind", kind.wire); put("region", region) })

suspend fun MarqueeCore.seerrSearch(link: SeerrLink, text: String, page: Int = 1): SeerrReply<List<SeerrTitle>> =
	call("seerr.search", link.params { put("text", text); put("page", page) })

/** A title page: Seerr's details, this account and the server's settings read together and the rules applied. */
suspend fun MarqueeCore.seerrDetails(
	link: SeerrLink, kind: SeerrKind, tmdbId: Int, pulsarr: Boolean, serverId: String?,
): SeerrReply<SeerrDetails> = call(
	"seerr.details",
	link.params {
		put("kind", kind.wire)
		put("tmdbID", tmdbId)
		put("pulsarr", pulsarr)
		if (serverId != null) put("serverID", serverId)
	},
)

/** Asks Seerr for a title. For a series, [seasons] are the ones chosen; Core drops the ones that cannot be requested. */
suspend fun MarqueeCore.seerrRequest(
	link: SeerrLink, kind: SeerrKind, tmdbId: Int, seasons: Set<Int>, is4K: Boolean,
): SeerrReply<JsonObject> = call(
	"seerr.request",
	link.params {
		put("kind", kind.wire)
		put("tmdbID", tmdbId)
		put("is4K", is4K)
		put("seasons", buildJsonArray { seasons.sorted().forEach { add(it) } })
	},
)

/** The account's latest requests, newest first. */
suspend fun MarqueeCore.seerrRequests(link: SeerrLink, take: Int = 20): SeerrReply<List<SeerrRequest>> =
	call("seerr.requests", link.params { put("take", take) })

internal val SeerrKind.wire: String get() = if (this == SeerrKind.Movie) "movie" else "tv"

/** TMDB's image server, which Seerr's own pages load from. */
object TmdbImages {
	private val widths = intArrayOf(92, 154, 185, 342, 500, 780)

	/** The smallest size at least [pixels] wide, else the original. */
	fun url(path: String?, pixels: Int): String? {
		if (path == null || !path.startsWith("/")) return null
		val size = widths.firstOrNull { it >= pixels }?.let { "w$it" } ?: "original"
		return "https://image.tmdb.org/t/p/$size$path"
	}
}

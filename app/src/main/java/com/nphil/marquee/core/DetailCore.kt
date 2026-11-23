package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The detail page's bridge calls (bridge/Sources/MarqueeBridge/DetailCalls.swift) as typed suspend calls. Screens reach
// them through AppSession.onServer, which supplies the server and the address that answered last.

/** The full item: for a movie or episode its versions and tracks, for a show the episode up next. */
suspend fun MarqueeCore.item(server: PlexServer, remembered: String?, ratingKey: String): PlexItem =
	call("detail.item", ofItem(server, remembered, ratingKey))

/** A show's seasons or a season's episodes. */
suspend fun MarqueeCore.children(server: PlexServer, remembered: String?, ratingKey: String): List<PlexItem> =
	call("detail.children", ofItem(server, remembered, ratingKey))

/** A movie's or show's trailers and other bonus videos. */
suspend fun MarqueeCore.extras(server: PlexServer, remembered: String?, ratingKey: String): List<PlexItem> =
	call("detail.extras", ofItem(server, remembered, ratingKey))

/** Titles like [ratingKey], as shelves. */
suspend fun MarqueeCore.related(server: PlexServer, remembered: String?, ratingKey: String): List<PlexHub> =
	call("detail.related", ofItem(server, remembered, ratingKey))

/** Everything a person appears in across the server's libraries, by [PlexRole.id]. */
suspend fun MarqueeCore.titlesOf(server: PlexServer, remembered: String?, personId: String): List<PlexItem> =
	call("detail.person", serverParams(server, remembered) { put("personID", personId) })

/** The server's own copies of a catalogue title, found by its `plex://` guid. */
suspend fun MarqueeCore.copiesOf(server: PlexServer, remembered: String?, guid: String): List<PlexItem> =
	call("detail.matching", serverParams(server, remembered) { put("guid", guid) })

suspend fun MarqueeCore.setWatched(server: PlexServer, remembered: String?, ratingKey: String, watched: Boolean) {
	call<JsonObject>(
		"detail.setWatched",
		serverParams(server, remembered) {
			put("ratingKey", ratingKey)
			put("watched", watched)
		},
	)
}

/** What the server's owner allows and pays for. */
suspend fun MarqueeCore.serverInfo(server: PlexServer, remembered: String?): ServerInfo =
	call("detail.serverInfo", serverParams(server, remembered))

/**
 * What a delete may ask beside the server, each only where it is set up: [accountToken] is the signed-in profile's, for
 * the Watchlist, and null when no account is signed in to one.
 */
internal class DeleteServices(val accountToken: String?, val radarr: ArrLink?, val sonarr: ArrLink?, val seerr: SeerrLink?) {
	fun withSeerr(link: SeerrLink?) = DeleteServices(accountToken, radarr, sonarr, link)
}

/**
 * Deletes [item] from the server's disk and asks no other service, so the title can leave every list at once. The
 * services in [services] are named only so the ids they know the title by are read before the server forgets it. With
 * [DeleteOutcome.Deleted] comes the [Deletion.ticket] that [cleanUp] takes.
 */
internal suspend fun MarqueeCore.delete(
	server: PlexServer, remembered: String?, item: PlexItem, services: DeleteServices,
): SeerrReply<Deletion> = call(
	"detail.delete",
	serverParams(server, remembered) {
		put("item", identity(item))
		putServices(services)
	},
)

/**
 * The steps after [delete]: the Watchlist, Radarr or Sonarr, and Seerr, each answering for itself and none stopping the
 * next. [alreadyDeleted] lists the keys this session deleted before, because the server lists those episodes until its
 * trash is emptied. [previous] is an earlier answer of this call: only the steps that failed in it run again.
 */
internal suspend fun MarqueeCore.cleanUp(
	server: PlexServer, remembered: String?, ticket: JsonObject, alreadyDeleted: Set<String>, services: DeleteServices,
	previous: Deletion? = null,
): SeerrReply<Deletion> = call(
	"detail.cleanUp",
	serverParams(server, remembered) {
		put("ticket", ticket)
		put("alreadyDeleted", buildJsonArray { alreadyDeleted.forEach { add(it) } })
		putServices(services)
		if (previous != null) put("previous", previous.steps())
	},
)

/** Deletes one version of a title and its file, leaving the others. */
suspend fun MarqueeCore.deleteVersion(server: PlexServer, remembered: String?, ratingKey: String, mediaId: Int): Deletion =
	call(
		"detail.deleteVersion",
		serverParams(server, remembered) {
			put("ratingKey", ratingKey)
			put("mediaID", mediaId)
		},
	)

/** Where a file the player fetches itself is, and the headers that carry the token; the address holds none. */
suspend fun MarqueeCore.mediaUrl(server: PlexServer, remembered: String?, path: String): MediaUrl =
	call("detail.mediaURL", serverParams(server, remembered) { put("path", path) })

/** Whether the catalogue title [guid] is on the account's Watchlist. */
suspend fun MarqueeCore.onWatchlist(token: String, guid: String): Boolean =
	call<Flag>("watchlist.contains", watchlistParams(token, guid)).value

suspend fun MarqueeCore.addToWatchlist(token: String, guid: String) {
	call<JsonObject>("watchlist.add", watchlistParams(token, guid))
}

suspend fun MarqueeCore.removeFromWatchlist(token: String, guid: String) {
	call<JsonObject>("watchlist.remove", watchlistParams(token, guid))
}

private fun MarqueeCore.ofItem(server: PlexServer, remembered: String?, ratingKey: String) =
	serverParams(server, remembered) { put("ratingKey", ratingKey) }

private fun watchlistParams(token: String, guid: String) = buildJsonObject {
	put("token", token)
	put("guid", guid)
}

/** The title as the server and the Watchlist know it, in Plex's own key names. */
private fun identity(item: PlexItem): JsonObject = buildJsonObject {
	put("ratingKey", item.ratingKey)
	put("type", CoreJson.encodeToJsonElement(MediaType.serializer(), item.type))
	put("title", item.title)
	item.year?.let { put("year", it) }
	putIfPresent("guid", item.guid)
	putIfPresent("parentGuid", item.parentGuid)
	putIfPresent("grandparentGuid", item.grandparentGuid)
	putIfPresent("parentTitle", item.parentTitle)
	putIfPresent("grandparentTitle", item.grandparentTitle)
	putIfPresent("parentRatingKey", item.parentRatingKey)
	putIfPresent("grandparentRatingKey", item.grandparentRatingKey)
}

internal fun JsonObjectBuilder.putServices(services: DeleteServices) {
	if (services.accountToken != null) put("token", services.accountToken)
	services.radarr?.let { put("radarr", CoreJson.encodeToJsonElement(ArrLink.serializer(), it)) }
	services.sonarr?.let { put("sonarr", CoreJson.encodeToJsonElement(ArrLink.serializer(), it)) }
	services.seerr?.let { put("seerr", it.params()) }
}

/** What each step of an earlier cleanup came to, which is all a retry needs besides the ticket. */
internal fun Deletion.steps(): JsonObject = buildJsonObject {
	put("watchlist", CoreJson.encodeToJsonElement(WatchlistCleanup.serializer(), watchlist))
	putIfPresent("guid", guid)
	put("arr", CoreJson.encodeToJsonElement(ServiceCleanup.serializer(), arr))
	put("requests", CoreJson.encodeToJsonElement(ServiceCleanup.serializer(), requests))
}

private fun JsonObjectBuilder.putIfPresent(name: String, value: String?) {
	if (value != null) put(name, value)
}

/** The server's switches that decide what a page offers. */
@Serializable
data class ServerInfo(
	/** Apps may delete media (the server's "Allow media deletion" setting). */
	val allowsMediaDeletion: Boolean = false,
	/** The owner has Plex Pass, which Boost dialog and Normalize loudness need. */
	val hasPlexPass: Boolean = false,
)

/**
 * How a delete stands. Straight after [delete] only [outcome] and [ticket] say anything; after [cleanUp] each step has
 * its own result.
 */
@Serializable
data class Deletion(
	val outcome: DeleteOutcome,
	val watchlist: WatchlistCleanup = WatchlistCleanup.Unchanged,
	/** The catalogue title the Watchlist cleanup touched or failed to touch. */
	val guid: String? = null,
	/** Radarr for a movie, Sonarr for a show. */
	val arr: ServiceCleanup = ServiceCleanup.NotConnected,
	val requests: ServiceCleanup = ServiceCleanup.NotConnected,
	/**
	 * What [cleanUp] needs, kept as the bridge wrote it. It comes with [DeleteOutcome.Deleted] and stays after a cleanup
	 * only while a step failed that asking again may fix.
	 */
	val ticket: JsonObject? = null,
)

@Serializable
enum class DeleteOutcome {
	/** The title is gone: deleted now, or gone already. */
	@SerialName("deleted")
	Deleted,

	/** Deleting is off on the server, or this profile may not delete. Asking again cannot help. */
	@SerialName("notAllowed")
	NotAllowed,

	/** A server fault or no answer; Retry may work. */
	@SerialName("failed")
	Failed,
}

@Serializable
enum class WatchlistCleanup {
	/** Nothing to remove. */
	@SerialName("unchanged")
	Unchanged,

	@SerialName("removed")
	Removed,

	/** The title is on the Watchlist and could not be taken off it. */
	@SerialName("failed")
	Failed,
}

/** What became of the title in Radarr, Sonarr or Seerr. */
@Serializable
enum class ServiceCleanup {
	/** Marquee has no connection to the service, so it was not asked. */
	@SerialName("notConnected")
	NotConnected,

	/** Nothing to do there: the delete left part of the show in place. */
	@SerialName("notApplicable")
	NotApplicable,

	/** The server gave the title no id the service knows it by. Asking again cannot help. */
	@SerialName("unmatched")
	Unmatched,

	/** The title is off the service: removed now, or not there to begin with. */
	@SerialName("removed")
	Removed,

	/** The service refused the account or the key. Asking again cannot help. */
	@SerialName("refused")
	Refused,

	/** An error or no answer; Retry may work. */
	@SerialName("failed")
	Failed,
}

/** A file the player fetches itself: its address, without the token, and the headers that carry it. */
@Serializable
data class MediaUrl(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
internal class Flag(val value: Boolean)

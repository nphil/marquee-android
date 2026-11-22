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
 * Deletes [item] from the server's disk. [alreadyDeleted] lists the keys this session deleted before, because the
 * server lists those episodes until its trash is emptied; [accountToken] lets a deleted title leave the Watchlist
 * too, and is null when no account is signed in to one.
 */
suspend fun MarqueeCore.delete(
	server: PlexServer, remembered: String?, item: PlexItem, alreadyDeleted: Set<String>, accountToken: String?,
): Deletion = call(
	"detail.delete",
	serverParams(server, remembered) {
		put("item", identity(item))
		put("alreadyDeleted", buildJsonArray { alreadyDeleted.forEach { add(it) } })
		if (accountToken != null) put("token", accountToken)
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
	putIfPresent("guid", item.guid)
	putIfPresent("parentGuid", item.parentGuid)
	putIfPresent("grandparentGuid", item.grandparentGuid)
	putIfPresent("parentRatingKey", item.parentRatingKey)
	putIfPresent("grandparentRatingKey", item.grandparentRatingKey)
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

/** How a delete ended, and what became of the title's Watchlist entry. */
@Serializable
data class Deletion(
	val outcome: DeleteOutcome,
	val watchlist: WatchlistCleanup = WatchlistCleanup.Unchanged,
	/** The catalogue title the Watchlist cleanup touched or failed to touch. */
	val guid: String? = null,
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

/** A file the player fetches itself: its address, without the token, and the headers that carry it. */
@Serializable
data class MediaUrl(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
internal class Flag(val value: Boolean)

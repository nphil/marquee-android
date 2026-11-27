package com.nphil.marquee.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The bridge's browse calls (bridge/Sources/MarqueeBridge/BrowseCalls.swift, whose header lists them) as typed suspend
// calls. Screens reach them through BrowseSource.onServer, which supplies the server and the address that answered last.

/** Home's rows from the server, or from the last recording when [HomeLoad.mode] is saved. */
suspend fun MarqueeCore.homeRows(server: PlexServer, remembered: String?, load: HomeLoad): HomeReply =
	call("home.load", serverParams(server, remembered) { merge(CoreJson.encodeToJsonElement(HomeLoad.serializer(), load).jsonObject) })

/** The rows of a load that answered [HomeReply.late], with the shows watched again once they are read; null when a newer load replaced it. */
suspend fun MarqueeCore.followHome(server: PlexServer, remembered: String?, run: String, dismissed: JsonObject?): List<HomeRow>? =
	call<FollowReply>(
		"home.follow",
		serverParams(server, remembered) {
			put("run", run)
			if (dismissed != null) put("dismissed", dismissed)
		},
	).rows

/** Every row Home could show, each with its first few titles, for the Home Rows editor. */
suspend fun MarqueeCore.homeCatalogue(server: PlexServer, remembered: String?): List<HomeRow> =
	call("home.catalogue", serverParams(server, remembered))

/** The [dismissed] cards plus [card], which stays off Continue Watching until the show is viewed again. */
suspend fun MarqueeCore.dismissRewatch(server: PlexServer, remembered: String?, card: PlexItem, dismissed: JsonObject): JsonObject =
	call("home.dismissRewatch", rewatchParams(server, remembered, card, dismissed))

/** Undoes [dismissRewatch]. */
suspend fun MarqueeCore.restoreRewatch(server: PlexServer, remembered: String?, card: PlexItem, dismissed: JsonObject): JsonObject =
	call("home.restoreRewatch", rewatchParams(server, remembered, card, dismissed))

/** Takes Plex's own Continue Watching card off the row on the server. */
suspend fun MarqueeCore.removeFromContinueWatching(server: PlexServer, remembered: String?, ratingKey: String) {
	call<JsonObject>("library.removeFromContinueWatching", serverParams(server, remembered) { putKeys(listOf(ratingKey)) })
}

/** A page of a library's titles, in [query]'s order. */
suspend fun MarqueeCore.libraryPage(
	server: PlexServer, remembered: String?, section: String, query: SectionQuery, start: Int, size: Int,
): Page<PlexItem> = call(
	"library.items",
	serverParams(server, remembered) {
		put("section", section)
		put("query", query.toJson())
		put("start", start)
		put("size", size)
	},
)

/** The letters of the A–Z rail with their counts and positions. */
suspend fun MarqueeCore.libraryLetters(server: PlexServer, remembered: String?, section: String, query: SectionQuery): List<AlphabetEntry> =
	call(
		"library.alphabet",
		serverParams(server, remembered) {
			put("section", section)
			put("query", query.toJson())
		},
	)

/** The genres the Genre filter offers. */
suspend fun MarqueeCore.libraryGenres(server: PlexServer, remembered: String?, section: String): List<PlexDirectory> =
	call("library.genres", serverParams(server, remembered) { put("section", section) })

suspend fun MarqueeCore.libraryCollections(server: PlexServer, remembered: String?, section: String, start: Int, size: Int): Page<PlexItem> =
	call(
		"library.collections",
		serverParams(server, remembered) {
			put("section", section)
			put("start", start)
			put("size", size)
		},
	)

/** What the server recommends in a library, as rows. */
suspend fun MarqueeCore.libraryRecommended(server: PlexServer, remembered: String?, section: String): List<PlexHub> =
	call("library.recommended", serverParams(server, remembered) { put("section", section) })

/** A page of a list the server named by [key], such as a hub's "See All". */
suspend fun MarqueeCore.serverList(server: PlexServer, remembered: String?, key: String, start: Int, size: Int): Page<PlexItem> =
	call(
		"library.list",
		serverParams(server, remembered) {
			put("key", key)
			put("start", start)
			put("size", size)
		},
	)

/** The titles with these rating keys as the server has them now; one it no longer has is left out. */
suspend fun MarqueeCore.refetchItems(server: PlexServer, remembered: String?, ratingKeys: Collection<String>): List<PlexItem> =
	call("library.refetch", serverParams(server, remembered) { putKeys(ratingKeys) })

/** Search groups for [query]: movies, shows, episodes and people. */
suspend fun MarqueeCore.searchHubs(server: PlexServer, remembered: String?, query: String, limit: Int = 10): List<PlexHub> =
	call(
		"search.hubs",
		serverParams(server, remembered) {
			put("query", query)
			put("limit", limit)
		},
	)

internal suspend fun MarqueeCore.releasedWatchlist(token: String): Set<String> =
	call("watchlist.released", buildJsonObject { put("token", token) })

/** A page of the account's plex.tv Watchlist. With a [snapshot] the answer is recorded, or in saved mode replayed. */
suspend fun MarqueeCore.watchlistPage(
	token: String, start: Int, size: Int, snapshot: SnapshotSpec? = null, mode: SnapshotMode = SnapshotMode.Live,
): Page<PlexItem> = call(
	"watchlist.items",
	buildJsonObject {
		put("token", token)
		put("start", start)
		put("size", size)
		if (snapshot != null) {
			put("snapshot", CoreJson.encodeToJsonElement(SnapshotSpec.serializer(), snapshot))
			put("mode", CoreJson.encodeToJsonElement(SnapshotMode.serializer(), mode))
		}
	},
)

/** Forgets the saved answers of one profile, or of every profile when [profile] is null. */
suspend fun MarqueeCore.removeSnapshots(directory: String, profile: String? = null) {
	call<JsonObject>(
		"snapshot.remove",
		buildJsonObject {
			put("directory", directory)
			if (profile != null) put("profile", profile)
		},
	)
}

private fun MarqueeCore.rewatchParams(server: PlexServer, remembered: String?, card: PlexItem, dismissed: JsonObject) =
	serverParams(server, remembered) {
		put("dismissed", dismissed)
		put("card", buildJsonObject { putRewatchCard(card) })
	}

private fun JsonObjectBuilder.putKeys(keys: Collection<String>) {
	put("ratingKeys", buildJsonArray { keys.forEach { add(it) } })
}

private fun JsonObjectBuilder.merge(json: JsonObject) {
	for ((name, value) in json) put(name, value)
}

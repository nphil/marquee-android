package com.nphil.marquee.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// What Home, the library grids, Search and the Watchlist read through the bridge
// (bridge/Sources/MarqueeBridge/BrowseWire.swift), and the rules the iPhone app keeps for them in MarqueeCore.

/** The library a row of Home comes from; [id] is its uuid, else its key. */
@Immutable
@Serializable
data class RowLibrary(val id: String, val key: String, val title: String)

/** One row Home can show. [shown] is the profile's layout's call: a Home hub is on by default, a library's is off. */
@Immutable
@Serializable
data class HomeRow(
	/** The hub's identifier, prefixed with its library's id for a library hub, as the layout names it. */
	val id: String,
	val title: String,
	/** Null for a Home hub. */
	val library: RowLibrary? = null,
	/** The row Continue Watching: Plex's own cards and the shows watched again. */
	val continueWatching: Boolean = false,
	val hub: PlexHub,
	val shown: Boolean = library == null,
)

/** The answer of `home.load`. */
@Immutable
@Serializable
data class HomeReply(
	val rows: List<HomeRow> = emptyList(),
	/** The shows watched again are still being read; [run] finishes them. */
	val late: Boolean = false,
	val run: String? = null,
	/** When the saved copy was recorded, in seconds since 1970; null when nothing was saved. */
	val savedAt: Double? = null,
)

@Serializable
internal class FollowReply(val rows: List<HomeRow>? = null)

/** Whether a read asks the server and records the answer, or answers from the last recording alone. */
@Serializable
enum class SnapshotMode {
	@SerialName("live") Live,
	@SerialName("saved") Saved,
}

/** Where one profile's saved answers live: a folder in the app's cache, one scope per server and profile. */
@Serializable
class SnapshotSpec(val directory: String, val server: String, val profile: String)

/** The payload of `home.load`. */
@Serializable
class HomeLoad(
	/** The libraries with a shown row ([HomeLayout.shownLibraries]); Home asks only these for their hubs. */
	val libraries: List<String>,
	/** Libraries switched off under Favorite Libraries, which the app has no page for yet. */
	val hidden: List<String> = emptyList(),
	/** The shows taken off Continue Watching: [RewatchDismissals] as the bridge hands them back. */
	val dismissed: JsonObject? = null,
	val mode: SnapshotMode,
	val snapshot: SnapshotSpec? = null,
	/** Rows are on screen already, so Home may wait longer for the shows watched again. */
	val showing: Boolean = false,
)

/** A Continue Watching card made for a show watched again, as far as taking it off the row needs. */
internal fun JsonObjectBuilder.putRewatchCard(card: PlexItem) {
	put("ratingKey", card.ratingKey)
	card.grandparentRatingKey?.let { put("grandparentRatingKey", it) }
	card.grandparentGuid?.let { put("grandparentGuid", it) }
	put("rewatchedAt", card.rewatchedAt ?: 0L)
}

// MARK: Home layout

/** A top-level row id without the part Plex rotates, so a hidden "Top Movies in <genre>" row stays hidden. */
object HomeIds {
	/**
	 * "Top Movies in <genre>" and "Top Movies with <person>" come with a new genre or person on each request and for
	 * each account (`movie.genre.1.3718`, `movie.genre.1.6`), so the row is `movie.genre.1`. Every other id is unchanged.
	 */
	fun stable(id: String): String {
		val parts = id.split('.')
		if (parts.size < 4) return id
		val last = parts.last()
		if (last.isEmpty() || '/' in last || !parts[parts.size - 2].all(Char::isDigit)) return id
		val kind = parts.dropLast(2).joinToString(".")
		if (!kind.endsWith(".genre") && !kind.endsWith(".by.actor.or.director")) return id
		return parts.dropLast(1).joinToString(".")
	}
}

/** One row in a profile's layout. */
@Immutable
@Serializable
data class Placement(
	val id: String,
	val shown: Boolean = true,
	/** The library whose hubs carry the row; null for a Home hub. */
	val library: String? = null,
)

/**
 * One profile's choice of Home rows: which show and in what order, as the iPhone app keeps it. Rows never placed
 * follow the placed ones in the server's order with their default visibility, so a hub the server adds later turns
 * up at the end. Placed rows the server stops sending keep their place for when they return.
 */
@Immutable
data class HomeLayout(val placements: List<Placement> = emptyList()) {
	/** Empty until the profile changes something, which is the server's order. */
	val isDefault: Boolean get() = placements.isEmpty()

	/** The libraries with a shown row; Home asks only these for their hubs. */
	val shownLibraries: Set<String> get() = placements.filter { it.shown }.mapNotNullTo(LinkedHashSet()) { it.library }

	/** [rows], in the server's order (Home hubs, then each library's), arranged by this layout. A repeated id keeps its first row. */
	fun arrange(rows: List<HomeRow>): List<HomeRow> {
		val byId = LinkedHashMap<String, HomeRow>()
		for (row in rows) byId.putIfAbsent(row.id, row)
		val serverOrder = byId.keys.toList()
		val arranged = ArrayList<HomeRow>(byId.size)
		for (placement in placements) {
			val row = byId.remove(placement.id) ?: continue
			arranged += row.copy(shown = placement.shown)
		}
		for (id in serverOrder) byId[id]?.let(arranged::add)
		return arranged
	}

	/** The layout that makes [arranged] (an [arrange] result, edited) the order. Each placed row missing from it stays right after the row it followed. */
	fun saving(arranged: List<HomeRow>): HomeLayout {
		val saved = arranged.mapTo(ArrayList()) { Placement(it.id, it.shown, it.library?.id) }
		val present = saved.mapTo(HashSet()) { it.id }
		var previous: String? = null
		for (placement in placements) {
			if (placement.id !in present) {
				val after = previous?.let { id -> saved.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
				saved.add(if (after == null) 0 else after + 1, placement)
			}
			previous = placement.id
		}
		return of(saved)
	}

	/** The JSON the app keeps, which MarqueeCore's HomeLayout reads too. */
	fun encode(): String = CoreJson.encodeToString(Saved.serializer(), Saved(placements))

	@Serializable
	private class Saved(val placements: List<Placement>)

	companion object {
		/** [placements] by their stable ids, a repeated id keeping its first. */
		fun of(placements: List<Placement>): HomeLayout {
			val seen = HashSet<String>()
			return HomeLayout(
				placements.map { it.copy(id = HomeIds.stable(it.id)) }.filter { seen.add(it.id) },
			)
		}

		/** Reads what [encode] wrote or an older version kept; a placement that no longer reads is dropped, not every row's. */
		fun decode(text: String?): HomeLayout {
			if (text.isNullOrEmpty()) return HomeLayout()
			val list = runCatching { CoreJson.parseToJsonElement(text).jsonObject["placements"] as? JsonArray }.getOrNull()
				?: return HomeLayout()
			return of(list.mapNotNull { runCatching { CoreJson.decodeFromJsonElement(Placement.serializer(), it) }.getOrNull() })
		}
	}
}

// MARK: Library grids

/** What a library grid lists: the item type, a sort and its direction, the Unwatched filter and a genre. */
@Immutable
data class SectionQuery(
	val type: MediaType,
	val sort: Sort = Sort.Title,
	/** Runs the sort the other way from its first order: Z–A, oldest first, lowest first. Random has no direction. */
	val reversed: Boolean = false,
	val unwatchedOnly: Boolean = false,
	/** An entry of the library's genres. */
	val genre: PlexDirectory? = null,
) {
	/** The two directions of a sort in the View menu, first order then reversed. */
	@Immutable
	class Orders(val first: String, val reversed: String)

	/** A field to sort by; [raw] is the server's sort key. */
	enum class Sort(val raw: String, val title: String, val orders: Orders?) {
		Title("titleSort", "Title", Orders("A–Z", "Z–A")),
		RecentlyAdded("addedAt", "Date Added", Orders("Newest First", "Oldest First")),
		ReleaseDate("originallyAvailableAt", "Release Date", Orders("Newest First", "Oldest First")),
		Rating("rating", "Score", Orders("Highest First", "Lowest First")),
		AudienceRating("audienceRating", "Audience Score", Orders("Highest First", "Lowest First")),
		RecentlyWatched("lastViewedAt", "Date Viewed", Orders("Newest First", "Oldest First")),
		Plays("viewCount", "Plays", Orders("Highest First", "Lowest First")),
		Random("random", "Random", null),
	}

	/** The A–Z order, the only one the jump rail's letters follow. */
	val isAlphabetical: Boolean get() = sort == Sort.Title && !reversed

	/** Any filter the chips can name. */
	val isFiltered: Boolean get() = unwatchedOnly || genre != null

	internal fun toJson(): JsonObject = buildJsonObject {
		put("type", CoreJson.encodeToJsonElement(MediaType.serializer(), type))
		put("sort", sort.raw)
		if (reversed) put("reversed", true)
		if (unwatchedOnly) put("unwatchedOnly", true)
		genre?.let {
			put("genre", buildJsonObject {
				put("key", it.key)
				put("title", it.title)
			})
		}
	}
}

/** One page of a list, with the size of the whole. */
@Immutable
@Serializable
class Page<T>(val items: List<T>, val total: Int)

/** One letter of the A–Z rail, with the position of its first title in the sorted list. */
@Immutable
@Serializable
data class AlphabetEntry(val character: String, val count: Int, val offset: Int)

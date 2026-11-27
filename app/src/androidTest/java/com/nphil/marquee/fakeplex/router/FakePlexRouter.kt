package com.nphil.marquee.fakeplex.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** Where the fixture files come from: a folder on the JVM, the androidTest assets on a device. */
fun interface Fixtures {
	/** The bytes of the file at [path] (relative, `/`-separated), or null when there is none. */
	fun read(path: String): ByteArray?
}

/** One request as the router sees it: the host and path the app asked for, not where a transport sent them. */
class FakeRequest(
	val method: String,
	val host: String,
	/** Decoded, `/` for the root. */
	val path: String,
	/** Decoded query items in order; a null value is an item without one. */
	val query: List<Pair<String, String?>> = emptyList(),
	val headers: Map<String, String> = emptyMap(),
) {
	fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

	fun queryValue(name: String): String? = query.firstOrNull { it.first == name }?.second
}

class FakeReply(
	val status: Int,
	val body: ByteArray,
	val contentType: String,
	val headers: Map<String, String> = emptyMap(),
)

/**
 * The fixture rules of the iPhone app's `FakePlexRouter` (core/App/Testing/FakePlex.swift), minus SpeechDock's
 * alignment window and the Live TV time shift: what `core/UITests/FakePlex/README.md` says a request reads. Plain
 * Kotlin so a JVM test can check the rules. A router is one fake account's state: its writes and watch marks live
 * as long as it does, and the fixture files never change.
 */
class FakePlexRouter(private val fixtures: Fixtures) {
	private val lock = Any()

	/** The paths written to so far as `a_b` for `/a/b`, latest last, each once. */
	private val writes = mutableListOf<String>()

	private class WatchedWrite(val key: String, val watched: Boolean)

	private val watchedWrites = mutableListOf<WatchedWrite>()
	private val removedFromContinueWatching = mutableSetOf<String>()

	private val filler by lazy { ByteArray(8 shl 20) }

	private val hosts: JsonObject? by lazy { load("hosts.json") as? JsonObject }

	/** False for a host `hosts.json` lists as unreachable: the transport should fail to connect. */
	fun isReachable(host: String): Boolean = machineIdentifier(host) != null

	/** The reply for [request]; null when its host cannot be reached. */
	fun answer(request: FakeRequest): FakeReply? {
		val host = request.host
		val machine = machineIdentifier(host) ?: return null
		val path = request.path
		val query = request.query
		val method = request.method.uppercase()
		val prefix = prefix(host)

		if (path == "/identity") {
			return json(buildJsonObject {
				put("MediaContainer", buildJsonObject {
					put("size", 0)
					put("machineIdentifier", machine)
				})
			})
		}
		if (path == "/photo/:/transcode") return image(request.queryValue("url") ?: "")
		if (path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
			if (host == "image.tmdb.org") {
				val parts = path.split('/').filter { it.isNotEmpty() }
				if (parts.size < 4 || parts[0] != "t" || parts[1] != "p") return FakeReply(404, ByteArray(0), "text/plain")
				return image("/" + parts.drop(3).joinToString("/"))
			}
			return image(path)
		}
		if (path.startsWith("/library/parts/") && path.endsWith("/indexes/sd")) {
			val bif = file("pms$path.bif") ?: return json(buildJsonObject { put("MediaContainer", buildJsonObject { put("size", 0) }) }, 404)
			return FakeReply(200, bif, "application/octet-stream")
		}

		synchronized(lock) {
			if (method != "GET") wrote(path)
			if (path == "/:/scrobble" || path == "/:/unscrobble") {
				request.queryValue("key")?.let { watchedWrites += WatchedWrite(it, path == "/:/scrobble") }
			}
			if (path == "/actions/removeFromContinueWatching") {
				request.queryValue("ratingKey")?.let { removedFromContinueWatching += it }
			}
		}
		// The fixtures keep a deleted title, as a server lists one until its trash is emptied.
		if (method == "DELETE") return json(JsonObject(emptyMap()))

		val base = prefix + (if (path == "/") "/root" else path)
		val range = request.header("Range")
		if (method == "GET" && prefix == "pms") {
			file("$base.bin")?.let { return binary(it, range) }
			// A file without a fixture answers a ranged read with filler: the link probe reads the start of the file.
			if (path.startsWith("/library/parts/") && range != null) return binary(filler, range)
		}
		val sourceFixture = if (prefix == "pms" && method == "GET") fixture("servers/$host/$base", query) else null
		val found = sourceFixture ?: fixture(base, query) ?: derived(base, query)
		if (found != null) {
			if (found !is JsonObject) return json(found)
			val status = (found["__status"] as? JsonPrimitive)?.intOrNull ?: 200
			var body = JsonObject(found.filterKeys { it != "__status" && it != "__anchor" })
			if (prefix == "pms") body = withWatchedWrites(body)
			body = if (path == "/hubs/search" && request.queryValue("query") != null) {
				search(body, request.queryValue("query")!!, request.queryValue("limit")?.toIntOrNull())
			} else {
				list(body, query, paging(request))
			}
			return json(body, status)
		}
		if (method != "GET" || path.contains("/:/") || path.startsWith("/actions/")) return json(JsonObject(emptyMap()))
		file("$base.srt")?.let { return FakeReply(200, it, "text/plain; charset=utf-8") }
		return json(buildJsonObject { put("MediaContainer", buildJsonObject { put("size", 0) }) }, 404)
	}

	private fun wrote(path: String) {
		val name = path.split('/').filter { it.isNotEmpty() }.joinToString("_")
		writes.remove(name)
		writes += name
	}

	// MARK: Watched state

	/**
	 * The titles of [obj] as the server would list them after the watch writes: a title takes the latest write for
	 * itself, its season or its show, and a title just marked watched or removed leaves Continue Watching.
	 */
	private fun withWatchedWrites(obj: JsonObject): JsonObject {
		val marks: List<WatchedWrite>
		val removed: Set<String>
		synchronized(lock) {
			marks = watchedWrites.toList()
			removed = removedFromContinueWatching.toSet()
		}
		if (marks.isEmpty() && removed.isEmpty()) return obj
		val container = obj["MediaContainer"] as? JsonObject ?: return obj

		fun latest(item: JsonObject): Boolean? {
			val keys = listOf("ratingKey", "parentRatingKey", "grandparentRatingKey").mapNotNull { (item[it] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
			return marks.lastOrNull { it.key in keys }?.watched
		}

		fun applied(items: List<JsonObject>, leavingWhenWatched: Boolean): List<JsonObject> = items.mapNotNull { item ->
			val watched = latest(item) ?: return@mapNotNull item
			if (watched && leavingWhenWatched) return@mapNotNull null
			val leaves = (item["leafCount"] as? JsonPrimitive)?.intOrNull
			val changed = item.toMutableMap()
			if (leaves != null && leaves > 0) {
				changed["viewedLeafCount"] = JsonPrimitive(if (watched) leaves else 0)
			} else {
				val count = (item["viewCount"] as? JsonPrimitive)?.intOrNull ?: 0
				changed["viewCount"] = JsonPrimitive(if (watched) maxOf(count, 1) else 0)
			}
			changed.remove("viewOffset")
			JsonObject(changed)
		}

		val result = container.toMutableMap()
		objects(container["Metadata"])?.let { result["Metadata"] = JsonArray(applied(it, leavingWhenWatched = false)) }
		objects(container["Hub"])?.let { hubs ->
			result["Hub"] = JsonArray(hubs.map { hub ->
				val items = objects(hub["Metadata"]) ?: return@map hub
				val continuing = (hub["hubIdentifier"] as? JsonPrimitive)?.contentOrNull == "home.continue"
				var kept = applied(items, leavingWhenWatched = continuing)
				if (continuing) kept = kept.filter { (it["ratingKey"] as? JsonPrimitive)?.contentOrNull !in removed }
				JsonObject(hub + mapOf("Metadata" to JsonArray(kept), "size" to JsonPrimitive(kept.size)))
			})
		}
		return JsonObject(obj + mapOf("MediaContainer" to JsonObject(result)))
	}

	// MARK: Fixtures

	private fun prefix(host: String): String = when {
		host == "plex.tv" || host == "clients.plex.tv" -> "plextv"
		host == "discover.provider.plex.tv" -> "discover"
		host.startsWith("bazarr") -> "bazarr"
		host.startsWith("seerr") -> "seerr"
		host.startsWith("speechdock") -> "speechdock"
		host.startsWith("homelabber") -> "homelabber"
		host.startsWith("ntfy") -> "ntfy"
		else -> "pms"
	}

	/** The identifier `/identity` reports for [host]; null when the host cannot be reached. */
	private fun machineIdentifier(host: String): String? {
		val entry = hosts?.get(host) ?: return "fake-server"
		return (entry as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
	}

	/** `<base>@after=<write>.json` of the latest write, then `<base>@<name>=<value>.json` of the query, then `<base>.json`. */
	private fun fixture(base: String, query: List<Pair<String, String?>>): JsonElement? {
		val after = synchronized(lock) { writes.reversed() }.map { "$base@after=$it.json" }
		val variants = query.mapNotNull { (name, value) -> if (value == null || '/' in value) null else "$base@$name=$value.json" }
		for (name in after + variants + "$base.json") load(name)?.let { return it }
		return null
	}

	private fun load(name: String): JsonElement? =
		file(name)?.let { runCatching { Json.parseToJsonElement(it.decodeToString()) }.getOrNull() }

	private fun file(name: String): ByteArray? = fixtures.read(name.trimStart('/'))

	private fun derived(base: String, query: List<Pair<String, String?>>): JsonElement? {
		if (base == "pms/library/all") return libraryWide()
		if (base.startsWith("pms/livetv/dvrs/") && base.endsWith("/tune")) return load("pms/livetv/tune.json")
		if (!base.endsWith("/firstCharacter")) return null
		val all = load(base.dropLast("firstCharacter".length) + "all.json") as? JsonObject ?: return null
		return firstCharacters(all, query)
	}

	/** `/library/all`: every section's list together. */
	private fun libraryWide(): JsonElement? {
		val sections = load("pms/library/sections.json") as? JsonObject ?: return null
		val directories = objects((sections["MediaContainer"] as? JsonObject)?.get("Directory")) ?: return null
		val items = directories.flatMap { directory ->
			val key = (directory["key"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
			val all = load("pms/library/sections/$key/all.json") as? JsonObject ?: return@flatMap emptyList()
			objects((all["MediaContainer"] as? JsonObject)?.get("Metadata")) ?: emptyList()
		}
		return buildJsonObject {
			put("MediaContainer", buildJsonObject {
				put("size", items.size)
				put("Metadata", JsonArray(items))
			})
		}
	}

	/** The A-Z counts of a grid, from its list. */
	private fun firstCharacters(all: JsonObject, query: List<Pair<String, String?>>): JsonElement {
		val byTitle = query.filter { it.first != "sort" } + ("sort" to "titleSort")
		val listed = list(all, byTitle, paging = null)
		val items = objects((listed["MediaContainer"] as? JsonObject)?.get("Metadata")) ?: emptyList()
		val counts = mutableListOf<Pair<String, Int>>()
		for (item in items) {
			val title = text(item["titleSort"]) ?: text(item["title"]) ?: ""
			val first = title.firstOrNull()?.let { if (it.isLetter()) it.toString().uppercase() else "#" } ?: "#"
			if (counts.lastOrNull()?.first == first) counts[counts.size - 1] = first to counts.last().second + 1 else counts += first to 1
		}
		val directories = counts.map { (character, size) ->
			buildJsonObject {
				put("key", character)
				put("title", character)
				put("size", size)
			}
		}
		return buildJsonObject {
			put("MediaContainer", buildJsonObject {
				put("size", directories.size)
				put("Directory", JsonArray(directories))
			})
		}
	}

	private fun image(at: String): FakeReply {
		val local = runCatching { URI(at).path }.getOrNull() ?: at
		val relative = local.trimStart('/')
		val data = if (relative.isEmpty()) null else file(relative)
		if (data == null) return FakeReply(404, ByteArray(0), "text/plain")
		return FakeReply(200, data, if (relative.endsWith(".png")) "image/png" else "image/jpeg")
	}

	private fun json(element: JsonElement, status: Int = 200) =
		FakeReply(status, Json.encodeToString(JsonElement.serializer(), element).encodeToByteArray(), "application/json")

	// MARK: Lists

	private class Paging(val start: Int, val size: Int)

	private fun paging(request: FakeRequest): Paging? {
		fun value(name: String) = (request.header(name) ?: request.queryValue(name))?.toIntOrNull()
		val start = value("X-Plex-Container-Start") ?: return null
		val size = value("X-Plex-Container-Size") ?: return null
		return Paging(start, size)
	}

	/** Filters, sorts and pages `MediaContainer.Metadata` the way a server does for these parameters. */
	private fun list(obj: JsonObject, query: List<Pair<String, String?>>, paging: Paging?): JsonObject {
		val container = obj["MediaContainer"] as? JsonObject ?: return obj
		var items = objects(container["Metadata"]) ?: return obj
		for ((name, value) in query) {
			if (value == null) continue
			when {
				name == "type" -> TYPE_CODES[value]?.let { type -> items = items.filter { text(it["type"]) == type } }
				name == "guid" -> items = items.filter { text(it["guid"]) == value }
				name == "unwatched" && value == "1" -> items = items.filter { ((it["viewCount"] as? JsonPrimitive)?.intOrNull ?: 0) == 0 }
				name == "unwatchedLeaves" && value == "1" ->
					items = items.filter { ((it["viewedLeafCount"] as? JsonPrimitive)?.intOrNull ?: 0) < ((it["leafCount"] as? JsonPrimitive)?.intOrNull ?: 0) }
				name == "sort" -> items = sorted(items, value)
			}
		}
		val result = container.toMutableMap()
		result["totalSize"] = JsonPrimitive(items.size)
		if (paging != null) {
			val start = paging.start.coerceIn(0, items.size)
			items = items.subList(start, minOf(start + maxOf(paging.size, 0), items.size))
			result["offset"] = JsonPrimitive(start)
		}
		result["size"] = JsonPrimitive(items.size)
		result["Metadata"] = JsonArray(items)
		return JsonObject(obj + mapOf("MediaContainer" to JsonObject(result)))
	}

	/** `field` or `field:desc`. Items without the field sort first, as if it were empty. */
	private fun sorted(items: List<JsonObject>, sort: String): List<JsonObject> {
		val parts = sort.split(':').filter { it.isNotEmpty() }
		val field = parts.firstOrNull() ?: ""
		val descending = parts.getOrNull(1) == "desc"
		fun key(item: JsonObject): JsonElement? = if (field == "titleSort") item["titleSort"] ?: item["title"] else item[field]
		fun less(lhs: JsonObject, rhs: JsonObject): Boolean {
			val left = key(lhs) ?: return key(rhs) != null
			val right = key(rhs) ?: return false
			if (left !is JsonPrimitive || right !is JsonPrimitive) return false
			if (left.isString && right.isString) return NaturalOrder.compare(left.content, right.content) < 0
			val a = left.doubleOrNull
			val b = right.doubleOrNull
			return a != null && b != null && a < b
		}
		// A stable insertion order on the comparator's "less" so equal and incomparable entries keep their place.
		val comparator = Comparator<JsonObject> { a, b ->
			val (x, y) = if (descending) b to a else a to b
			when {
				less(x, y) -> -1
				less(y, x) -> 1
				else -> 0
			}
		}
		return items.sortedWith(comparator)
	}

	/** Keeps the entries of each search group whose title contains [text], and the groups left with any. */
	private fun search(obj: JsonObject, needle: String, limit: Int?): JsonObject {
		val container = obj["MediaContainer"] as? JsonObject ?: return obj
		val hubs = objects(container["Hub"]) ?: return obj

		fun matches(entry: JsonObject) = listOf("title", "tag", "grandparentTitle").any { field ->
			text(entry[field])?.contains(needle, ignoreCase = true) == true
		}

		val kept = hubs.mapNotNull { hub ->
			val changed = hub.toMutableMap()
			var total = 0
			for (field in listOf("Metadata", "Directory")) {
				val entries = objects(hub[field]) ?: continue
				val matching = entries.filter(::matches).take(limit ?: Int.MAX_VALUE)
				changed[field] = JsonArray(matching)
				total += matching.size
			}
			if (total == 0) return@mapNotNull null
			changed["size"] = JsonPrimitive(total)
			JsonObject(changed)
		}
		return JsonObject(mapOf("MediaContainer" to JsonObject(container + mapOf("Hub" to JsonArray(kept)))))
	}

	// MARK: Bytes

	private fun binary(data: ByteArray, range: String?): FakeReply {
		if (range == null) return FakeReply(200, data, "application/octet-stream")
		val parts = if (range.startsWith("bytes=")) range.removePrefix("bytes=").split('-') else emptyList()
		val unsatisfiable = FakeReply(416, ByteArray(0), "application/octet-stream", mapOf("Content-Range" to "bytes */${data.size}"))
		if (parts.size != 2 || data.isEmpty()) return unsatisfiable
		val first = parts[0].toIntOrNull()
		val last = parts[1].toIntOrNull()
		val start: Int
		val end: Int
		if (parts[0].isEmpty() && last != null && last > 0) {
			start = maxOf(0, data.size - last)
			end = data.size - 1
		} else if (first != null && first >= 0 && first < data.size && (parts[1].isEmpty() || (last != null && last >= first))) {
			start = first
			end = minOf(last ?: (data.size - 1), data.size - 1)
		} else {
			return unsatisfiable
		}
		return FakeReply(206, data.copyOfRange(start, end + 1), "application/octet-stream", mapOf("Content-Range" to "bytes $start-$end/${data.size}"))
	}

	private fun objects(element: JsonElement?): List<JsonObject>? =
		(element as? JsonArray)?.takeIf { array -> array.all { it is JsonObject } }?.map { it.jsonObject }

	private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

	private companion object {
		val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
		val TYPE_CODES = mapOf("1" to "movie", "2" to "show", "3" to "season", "4" to "episode", "18" to "collection")
	}
}

/** Case-insensitive order that reads digit runs as numbers, as Foundation's localized standard compare does. */
private object NaturalOrder : Comparator<String> {
	override fun compare(a: String, b: String): Int {
		var i = 0
		var j = 0
		while (i < a.length && j < b.length) {
			if (a[i].isDigit() && b[j].isDigit()) {
				var ie = i
				while (ie < a.length && a[ie].isDigit()) ie++
				var je = j
				while (je < b.length && b[je].isDigit()) je++
				val x = a.substring(i, ie).trimStart('0')
				val y = b.substring(j, je).trimStart('0')
				if (x.length != y.length) return x.length - y.length
				val c = x.compareTo(y)
				if (c != 0) return c
				i = ie
				j = je
			} else {
				val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
				if (c != 0) return c
				i++
				j++
			}
		}
		return (a.length - i) - (b.length - j)
	}
}

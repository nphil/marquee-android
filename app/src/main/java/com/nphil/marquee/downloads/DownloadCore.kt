package com.nphil.marquee.downloads

import com.nphil.marquee.core.Capabilities
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.HomeLabberLink
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.StartRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
internal enum class DownloadQuality(val key: String, val label: String) {
	Original("original", "Original"), High("high", "High"), Medium("medium", "Medium"), Low("low", "Low"),
}

@Serializable
internal data class DownloadPreferences(
	val quality: DownloadQuality = DownloadQuality.Original,
	val metered: Boolean = false,
	val autoDelete: Boolean = false,
)

@Serializable
internal data class DownloadTitle(
	val ratingKey: String,
	val title: String,
	val duration: Int,
	val year: Int? = null,
	val show: String? = null,
	val showKey: String? = null,
	val seasonKey: String? = null,
	val season: Int? = null,
	val episode: Int? = null,
	val guid: String? = null,
) {
	val episodeCode: String? get() = episode?.let { if (season == null) "E$it" else "S$season E$it" }
	val keys: Set<String> get() = setOfNotNull(ratingKey, showKey, seasonKey)
}

@Serializable
internal data class DownloadChoice(
	val id: String,
	val label: String,
	val resolution: String? = null,
	val bytes: Long? = null,
	val target: JsonObject? = null,
	val refusal: String? = null,
	/** "measured" from HomeLabber's scores or "estimated" for a conversion; null for Original. */
	val basis: String? = null,
)

@Serializable
internal data class DownloadChoices(
	val title: DownloadTitle,
	val options: List<DownloadChoice>,
	val preferred: String? = null,
	val snapshot: String,
	val isAnime: Boolean,
	val countries: List<String>,
	val watch: DownloadWatch,
)

@Serializable
internal data class Conversion(val queue: Int, val item: Int)

@Serializable
internal enum class ConversionPhase {
	@SerialName("deciding") Deciding,
	@SerialName("waiting") Waiting,
	@SerialName("processing") Processing,
	@SerialName("available") Available,
	@SerialName("error") Error,
	@SerialName("expired") Expired,
	@SerialName("unknown") Unknown,
}

@Serializable
internal data class ConversionStatus(val status: ConversionPhase, val fraction: Double? = null)

@Serializable
internal data class DownloadSource(
	val url: String,
	val headers: Map<String, String>,
	val poster: String? = null,
	val subtitles: List<Sidecar>,
) {
	@Serializable
	data class Sidecar(val id: Int, val url: String)
}

/** Codable shape of Core's OfflineWatch. Dates are seconds from Foundation's reference date. */
@Serializable
internal data class DownloadWatch(
	val offset: Int,
	val watched: Boolean,
	val unsynced: Double? = null,
	val finishedUnsynced: Boolean = false,
	val finishedHere: Boolean = false,
	val scrobble: ScrobbleReceipt? = null,
)

@Serializable
internal data class ScrobbleReceipt(val previousViewCount: Int, val receivedAt: Double)

@Serializable
internal data class SyncedWatch(val watch: DownloadWatch, val changedServer: Boolean)

/** Ephemeral credentials of the matched session, never written into the download index or a scheduled job. */
internal class DownloadServer(val server: PlexServer, val remembered: String?)

internal suspend fun MarqueeCore.downloadChoices(
	server: DownloadServer, key: String, index: Int, quality: DownloadQuality, capabilities: Capabilities,
	homeLabber: HomeLabberLink? = null,
): DownloadChoices = downloadCall("choices", server) {
	// With HomeLabber set up, the tiers use its measured bitrates for the titles it has scored.
	if (homeLabber != null) put("homelabber", CoreJson.encodeToJsonElement(homeLabber))
	put("ratingKey", key)
	put("mediaIndex", index)
	put("quality", quality.key)
	put("capabilities", CoreJson.encodeToJsonElement(capabilities))
}

internal suspend fun MarqueeCore.downloadEpisodes(server: DownloadServer, key: String, show: Boolean): List<String> =
	downloadCall("episodes", server) {
		put("ratingKey", key)
		put("show", show)
	}

internal suspend fun MarqueeCore.downloadSource(server: DownloadServer, entry: DownloadEntry, snapshot: String): DownloadSource =
	downloadCall("source", server) {
		put("snapshot", snapshot)
		put("mediaIndex", entry.version)
		put("assets", !entry.assetsFetched)
		entry.conversion?.let { put("conversion", CoreJson.encodeToJsonElement(it)) }
	}

internal suspend fun MarqueeCore.downloadSourceFailed(server: DownloadServer, url: String) {
	downloadCall<JsonObject>("source.failed", server) { put("url", url) }
}

internal suspend fun MarqueeCore.downloadQueue(server: DownloadServer): Int =
	downloadCall("convert.queue", server) {}

internal suspend fun MarqueeCore.addConversion(server: DownloadServer, queue: Int, entry: DownloadEntry, target: JsonObject): Conversion =
	downloadCall("convert.add", server) {
		put("queue", queue)
		put("ratingKey", entry.title.ratingKey)
		put("mediaIndex", entry.version)
		put("target", target)
	}

internal suspend fun MarqueeCore.conversionStatus(server: DownloadServer, conversion: Conversion): ConversionStatus =
	conversionCall("status", server, conversion)

internal suspend fun MarqueeCore.conversionDecision(server: DownloadServer, conversion: Conversion): String =
	conversionCall("decision", server, conversion)

internal suspend fun MarqueeCore.restartConversion(server: DownloadServer, conversion: Conversion) {
	conversionCall<JsonObject>("restart", server, conversion)
}

internal suspend fun MarqueeCore.removeConversion(server: DownloadServer, conversion: Conversion) {
	conversionCall<JsonObject>("remove", server, conversion)
}

internal suspend fun MarqueeCore.updateDownloadWatch(watch: DownloadWatch, position: Int, finished: Boolean, at: Long): DownloadWatch =
	call("download.watch.update", buildJsonObject {
		put("watch", CoreJson.encodeToJsonElement(watch))
		put("position", position)
		put("finished", finished)
		put("at", at / 1000.0)
	})

internal suspend fun MarqueeCore.syncDownloadWatch(server: DownloadServer, entry: DownloadEntry): SyncedWatch =
	downloadCall("watch.sync", server) {
		put("ratingKey", entry.title.ratingKey)
		entry.title.guid?.let { put("guid", it) }
		put("duration", entry.title.duration)
		put("watch", CoreJson.encodeToJsonElement(entry.watch))
	}

internal suspend fun MarqueeCore.startDownload(local: LocalDownload, request: StartRequest): PlayPlan =
	call("play.local", buildJsonObject {
		CoreJson.encodeToJsonElement(StartRequest.serializer(), request).let { it as JsonObject }.forEach { (key, value) -> put(key, value) }
		put("snapshot", local.snapshot.path)
		local.decision?.let { put("decision", it.path) }
		put("file", local.media.path)
		put("version", local.entry.version)
		put("isAnime", local.entry.isAnime)
		put("countries", CoreJson.encodeToJsonElement(local.entry.countries))
		put("sidecars", CoreJson.encodeToJsonElement(local.subtitles))
		put("localOffset", local.entry.watch.offset)
	})

internal suspend fun MarqueeCore.closeDownloadPlayback(session: String) {
	call<JsonObject>("play.local.close", buildJsonObject { put("session", session) })
}

private suspend inline fun <reified T> MarqueeCore.conversionCall(method: String, server: DownloadServer, conversion: Conversion): T =
	downloadCall("convert.$method", server) {
		put("queue", conversion.queue)
		put("item", conversion.item)
	}

private suspend inline fun <reified T> MarqueeCore.downloadCall(
	method: String, server: DownloadServer, noinline fields: JsonObjectBuilder.() -> Unit,
): T = call("download.$method", serverParams(server.server, server.remembered, extra = fields))

package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class DvrSchedule(val rules: List<DvrRule>, val scheduled: List<DvrAiring>)

@Serializable
internal data class DvrRule(
	val id: String,
	val title: String,
	val isSeries: Boolean,
	val airingsType: String? = null,
	val library: String? = null,
	val settings: List<DvrSetting>,
)

@Serializable
internal enum class DvrMark(val label: String) {
	@SerialName("scheduled") Scheduled("Scheduled"),
	@SerialName("series") Series("Series scheduled"),
	@SerialName("recording") Recording("Recording"),
	@SerialName("failed") Failed("Recording failed"),
	@SerialName("recorded") Recorded("Recorded"),
}

@Serializable
internal data class DvrAiring(
	val id: String,
	val ruleID: String? = null,
	val mark: DvrMark,
	val isUpcoming: Boolean,
	val isRecording: Boolean,
	val title: String,
	val episodeCode: String? = null,
	val episodeTitle: String? = null,
	val channelKey: String? = null,
	val channelNumber: String? = null,
	val start: Double,
)

@Serializable
internal data class DvrOption(
	val type: Int,
	val title: String,
	val isSeries: Boolean,
	val library: String? = null,
	val settings: List<DvrSetting>,
)

@Serializable
internal data class DvrSetting(val id: String, val type: String, val value: String, val choices: List<Choice>) {
	@Serializable data class Choice(val value: String, val label: String)
}

@Serializable
internal data class DvrItem(
	val ratingKey: String,
	val titleKey: String,
	val mediaID: Int,
	val title: String,
	val episodeCode: String? = null,
	val episodeTitle: String? = null,
	val year: Int? = null,
	val thumb: String? = null,
	val recordedAt: Double? = null,
	val duration: Int,
	val viewOffset: Int,
)

internal suspend fun MarqueeCore.dvrSchedule(server: PlexServer, remembered: String?): DvrSchedule =
	call("live.dvr.schedule", serverParams(server, remembered))

internal suspend fun MarqueeCore.dvrOptions(server: PlexServer, remembered: String?, guid: String): List<DvrOption> =
	call("live.dvr.options", serverParams(server, remembered) { put("guid", guid) })

internal suspend fun MarqueeCore.dvrRecord(server: PlexServer, remembered: String?, guid: String, type: Int, values: Map<String, String>) {
	call<JsonObject>("live.dvr.record", serverParams(server, remembered) {
		put("guid", guid)
		put("type", type)
		put("values", dvrValues(values))
	})
}

internal suspend fun MarqueeCore.dvrUpdate(server: PlexServer, remembered: String?, id: String, values: Map<String, String>) {
	call<JsonObject>("live.dvr.update", serverParams(server, remembered) { put("id", id); put("values", dvrValues(values)) })
}

internal suspend fun MarqueeCore.skipUpcoming(server: PlexServer, remembered: String?, id: String) {
	call<JsonObject>("live.dvr.skipUpcoming", serverParams(server, remembered) { put("id", id) })
}

internal suspend fun MarqueeCore.dvrUnschedule(server: PlexServer, remembered: String?, airing: DvrAiring, deletingSeries: Boolean) {
	call<JsonObject>("live.dvr.unschedule", serverParams(server, remembered) {
		put("id", airing.id)
		put("isRecording", airing.isRecording)
		put("deletingSeries", deletingSeries)
	})
}

internal suspend fun MarqueeCore.dvrDelete(server: PlexServer, remembered: String?, id: String) {
	call<JsonObject>("live.dvr.delete", serverParams(server, remembered) { put("id", id) })
}

internal suspend fun MarqueeCore.dvrRecorded(server: PlexServer, remembered: String?): List<DvrItem> =
	call("live.dvr.recorded", serverParams(server, remembered))

private fun dvrValues(values: Map<String, String>) =
	CoreJson.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), values)

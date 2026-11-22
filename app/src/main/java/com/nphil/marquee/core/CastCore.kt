package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// Times cross the Swift bridge in seconds, not the Cast SDK's milliseconds.
@Serializable
internal class CastStartRequest(
	val ratingKey: String,
	val session: String?,
	val startAt: Double?,
	val fromStart: Boolean?,
	val mediaIndex: Int?,
	val quality: Int,
	val directPlay: DirectPlayMode,
	val now: Double,
	val audio: Int?,
	val subtitle: SubtitlePick?,
	val memory: JsonElement? = null,
	val receiverModel: String?,
	val replacing: Int?,
	val receiverFailed: Boolean?,
	val airPlay: Boolean = false,
	val recordingMediaID: Int? = null,
)

/** Only the stream URL and poster prepared here carry a short-lived token usable by the receiver. */
@Serializable
internal class CastPlan(val plan: PlayPlan, val contentType: String, val poster: String? = null)

@Serializable
internal data class CastStatus(
	val mediaSessionId: Int,
	val player: String,
	val idle: String? = null,
	val position: Double,
	val rate: Double,
)

@Serializable
internal class CastProgress(val state: String, val position: Double, val end: CastEnd? = null)

@Serializable
internal enum class CastEnd {
	@SerialName("finished") Finished,
	@SerialName("failed") Failed,
	@SerialName("stopped") Stopped,
}

internal suspend fun MarqueeCore.startCast(server: PlexServer, remembered: String?, request: CastStartRequest): CastPlan =
	call("cast.start", serverParams(server, remembered) {
		CoreJson.encodeToJsonElement(CastStartRequest.serializer(), request).jsonObject.forEach { (name, value) -> put(name, value) }
	})

internal suspend fun MarqueeCore.castProgress(session: String, now: Double, status: CastStatus?): CastProgress =
	call("cast.progress", buildJsonObject {
		put("session", session)
		put("now", now)
		if (status != null) put("status", CoreJson.encodeToJsonElement(CastStatus.serializer(), status))
	})

internal suspend fun MarqueeCore.endCast(session: String) {
	call<JsonObject>("cast.end", buildJsonObject { put("session", session) })
}

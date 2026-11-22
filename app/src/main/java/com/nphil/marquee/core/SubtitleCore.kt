package com.nphil.marquee.core

import android.util.Base64
import com.nphil.marquee.data.SubtitleReveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal enum class SubtitleService(val method: String, val title: String) {
	Bazarr("bazarr", "Bazarr"),
	SpeechDock("speech", "SpeechDock"),
}

/** The API key is sent only to the saved Bazarr origin and is never included in logs. */
@Serializable
internal data class BazarrLink(val address: String, val apiKey: String) {
	override fun toString() = "BazarrLink"
}

@Serializable
internal data class SubtitleAddress(val address: String? = null, val problem: String? = null)

@Serializable
internal data class SubtitleConnection(
	val version: String? = null,
	val sonarr: String? = null,
	val radarr: String? = null,
	val problem: String? = null,
)

internal suspend fun MarqueeCore.subtitleAddress(service: SubtitleService, address: String, key: String): SubtitleAddress =
	call("subtitles.${service.method}.address", addressParams(service, address, key))

internal suspend fun MarqueeCore.testSubtitleService(service: SubtitleService, address: String, key: String): SubtitleConnection =
	call("subtitles.${service.method}.status", addressParams(service, address, key))

private fun addressParams(service: SubtitleService, address: String, key: String) = buildJsonObject {
	put("address", address)
	if (service == SubtitleService.Bazarr) put("key", key)
}

/** Names the file the live stream is actually playing. Downloaded files do not have an online subtitle source. */
internal data class SubtitleSource(val session: String, val ratingKey: String, val partId: Int) {
	fun fields(to: JsonObjectBuilder) = with(to) {
		put("session", session)
		put("ratingKey", ratingKey)
		put("partId", partId)
	}
}

internal fun PlayPlan.subtitleSource(): SubtitleSource? {
	val url = stream?.url ?: return null
	if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
	val part = item.media.getOrNull(mediaIndex)?.parts?.firstOrNull() ?: return null
	return SubtitleSource(session, item.ratingKey, part.id)
}

@Serializable
internal data class FindBanner(val tone: Tone, val title: String, val detail: String) {
	@Serializable
	enum class Tone {
		@SerialName("busy") Busy,
		@SerialName("found") Found,
		@SerialName("missing") Missing,
	}

	companion object {
		fun busy(detail: String) = FindBanner(Tone.Busy, "Finding English subtitles", detail)
	}
}

@Serializable
internal data class FindSubtitlesReply(val banner: FindBanner, val tracks: PlayTracks? = null, val trackId: Int? = null)

internal suspend fun MarqueeCore.findSubtitles(source: SubtitleSource, request: String, bazarr: BazarrLink?): FindSubtitlesReply =
	call("subtitles.find", buildJsonObject {
		source.fields(this)
		put("request", request)
		if (bazarr != null) put("bazarr", CoreJson.encodeToJsonElement(BazarrLink.serializer(), bazarr))
	})

internal suspend fun MarqueeCore.findSubtitlesStatus(session: String, request: String): FindBanner? =
	call("subtitles.find.status", buildJsonObject {
		put("session", session)
		put("request", request)
	})

@Serializable
internal data class SpeechTimingReply(
	val active: Boolean,
	val nextAtMs: Long? = null,
	val busy: Boolean = false,
	val cues: List<TimedSpeechCue> = emptyList(),
)

@Serializable
internal data class TimedSpeechCue(val startMs: Long, val text: String, val starts: List<Double>)

internal suspend fun MarqueeCore.prepareSpeechTiming(
	source: SubtitleSource, subtitle: Int, playback: String, address: String, bytes: ByteArray,
): SpeechTimingReply = withContext(Dispatchers.Default) {
	call("subtitles.speech.prepare", buildJsonObject {
		source.fields(this)
		put("subtitle", subtitle)
		put("playback", playback)
		put("address", address)
		put("digest", MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
		put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
	})
}

internal suspend fun MarqueeCore.alignSpeechTiming(session: String, playback: String, dueMs: Long): SpeechTimingReply =
	call("subtitles.speech.align", buildJsonObject {
		put("session", session)
		put("playback", playback)
		put("dueMs", dueMs)
	})

internal suspend fun MarqueeCore.stopSpeechTiming(session: String, playback: String) {
	call<JsonObject>("subtitles.speech.stop", buildJsonObject {
		put("session", session)
		put("playback", playback)
	})
}

@Serializable
internal data class RevealCue(val text: String, val durationMs: Double?, val measured: List<Double>?)

@Serializable
internal data class RevealWord(val start: Int, val end: Int, val atMs: Double)

internal suspend fun MarqueeCore.revealWords(mode: SubtitleReveal, cues: List<RevealCue>): List<List<RevealWord>> =
	call("subtitles.reveal", buildJsonObject {
		put("mode", mode.name.lowercase())
		put("cues", CoreJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(RevealCue.serializer()), cues))
	})

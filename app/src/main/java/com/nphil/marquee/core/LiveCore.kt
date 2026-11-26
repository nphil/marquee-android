package com.nphil.marquee.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Core's lineup entry, handed back unchanged when the bridge plans or tunes a channel. */
@Serializable
internal class GuideChannel(
	val id: String,
	val dvrKey: String,
	val number: String,
	val callSign: String,
	val title: String,
	val logo: String? = null,
	val isHD: Boolean,
	val device: JsonObject,
) {
	val label: String get() {
		val name = callSign.ifBlank { title }
		return when {
			number.isBlank() -> name
			name.isBlank() -> number
			else -> "$number $name"
		}
	}
}

/** Presentation fields of Core's normalized [GuideWindow] schedule, with dates in Unix seconds. */
@Serializable
internal class GuideProgramme(
	val id: String,
	val title: String,
	val episodeCode: String? = null,
	val episodeTitle: String? = null,
	val summary: String? = null,
	val start: Double,
	val end: Double,
	val isNew: Boolean,
	val isHD: Boolean,
	val thumb: String? = null,
	val art: String? = null,
	val contentRating: String? = null,
	val guid: String = "",
	val movie: Boolean = false,
	val liveEvent: Boolean = false,
) {
	fun isOn(at: Double): Boolean = start <= at && at < end
	fun progress(at: Double): Float = ((at - start) / (end - start)).coerceIn(0.0, 1.0).toFloat()
}

@Serializable
internal data class GuideGenre(val title: String, val key: String)

@Serializable
internal class GuideWindow(
	val channels: List<GuideChannel>,
	val airings: Map<String, List<GuideProgramme>>,
	val from: Double,
	val to: Double,
	val filters: List<GuideGenre>,
	val tunerOffline: Boolean,
	val run: String? = null,
) {
	/** The first cell that has not ended at the cursor. Core sorted and disambiguated the schedule before sending it. */
	fun firstEndingAfter(channel: String, at: Double): Int {
		val list = airings[channel].orEmpty()
		var low = 0
		var high = list.size
		while (low < high) {
			val middle = (low + high) ushr 1
			if (list[middle].end <= at) low = middle + 1 else high = middle
		}
		return low
	}

	fun programmes(channel: String, at: Double): Pair<GuideProgramme?, GuideProgramme?> {
		val list = airings[channel].orEmpty()
		val index = firstEndingAfter(channel, at)
		val first = list.getOrNull(index)
		return if (first != null && first.start <= at) first to list.getOrNull(index + 1) else null to first
	}
}

@Serializable
internal class LiveStream(val session: String, val url: String, val headers: Map<String, String>)

internal suspend fun MarqueeCore.liveGuide(
	server: PlexServer,
	remembered: String?,
	directory: String,
	now: Double,
	cached: Boolean = false,
	run: String? = null,
): GuideWindow? = call(
	when {
		cached -> "live.cached"
		run != null -> "live.extend"
		else -> "live.guide"
	},
	serverParams(server, remembered) {
		put("directory", directory)
		put("now", now)
		if (run != null) put("run", run)
	},
)

internal suspend fun MarqueeCore.liveGenre(server: PlexServer, remembered: String?, filter: GuideGenre): Set<String> =
	call("live.genre", serverParams(server, remembered) {
		put("filter", CoreJson.encodeToJsonElement(GuideGenre.serializer(), filter))
	})

internal suspend fun MarqueeCore.openLive(server: PlexServer, remembered: String?, channel: GuideChannel): LiveStream =
	call("live.open", serverParams(server, remembered) {
		put("channel", CoreJson.encodeToJsonElement(GuideChannel.serializer(), channel))
	})

internal suspend fun MarqueeCore.reportLive(session: String, state: String) {
	call<JsonObject>("live.report", buildJsonObject { put("session", session); put("state", state) })
}

internal suspend fun MarqueeCore.closeLive(session: String) {
	call<JsonObject>("live.close", buildJsonObject { put("session", session) })
}

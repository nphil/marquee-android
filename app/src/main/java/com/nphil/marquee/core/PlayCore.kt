package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The player's bridge calls (bridge/Sources/MarqueeBridge/PlayCalls.swift) as typed suspend calls. The bridge decides
// how an item plays (Core's route rules) and keeps the session's reports, queue and Auto quality; the app performs
// the route. Times are seconds unless a name ends in `Ms`; `now` is the app's monotonic clock in seconds.

/** The profile's Direct Play setting: the tablet plays what it can, always, or never. */
@Serializable
enum class DirectPlayMode {
	@SerialName("auto") Auto,
	@SerialName("force") Force,
	@SerialName("disabled") Disabled,
}

/** What the tablet's decoders and screen take. Matches Core's `DeviceCapabilities`. */
@Serializable
data class Capabilities(
	val hevc: Boolean,
	val hevcMain10: Boolean,
	val av1: Boolean,
	val hdrDisplay: Boolean,
	val dolbyVisionProfiles: List<Int>,
	/** Plex's audio codec names the player decodes itself. */
	val nativeAudioCodecs: List<String>,
	/** Plex's container names the player opens as they are. */
	val directContainers: List<String>,
)

/** The video's frame on screen, in pixels. */
@Serializable
data class Frame(val width: Int, val height: Int)

/** The app's timed read of the file: the bytes that arrived and the seconds they took; zeros for a read that failed. */
@Serializable
data class Reading(val bytes: Int, val seconds: Double)

/** Opens an item: what to play and where, and what the profile and the tablet allow. */
@Serializable
data class StartRequest(
	val ratingKey: String,
	/** The player screen's session. A restart, a quality move and the next item keep it; null opens one. */
	val session: String? = null,
	/** Where to start, in seconds; null resumes from the server's place. */
	val startAt: Double? = null,
	val fromStart: Boolean? = null,
	val mediaIndex: Int? = null,
	/** [QualityPreset.AUTO], 0 for Original, or a ceiling in kbit/s. */
	val quality: Int,
	val savesData: Boolean,
	val metered: Boolean,
	val directPlay: DirectPlayMode,
	val capabilities: Capabilities,
	/** Counts the tablet's network changes, so a link reading from another network is not reused. */
	val network: Int,
	val now: Double,
	val view: Frame? = null,
	/** The tablet's player failed on this item, so the server converts it. */
	val onDeviceFailed: Boolean? = null,
	/** Auto moved to this rung: a restart after [playLook] named it. */
	val move: Int? = null,
	/** The read [PlayPlan.probe] asked for. */
	val probe: Reading? = null,
)

/** How an item plays: the route Core chose, what opens it, and what the screen needs around it. */
@Serializable
data class PlayPlan(
	val session: String,
	val item: PlexItem,
	val mediaIndex: Int = 0,
	val startAt: Double = 0.0,
	/** The title's length in seconds; 0 when the server does not say. */
	val duration: Double = 0.0,
	val route: PlayRoute,
	/** What opens the stream; null while [probe] asks for a read of the link first. */
	val stream: PlayStream? = null,
	val probe: LinkProbe? = null,
	val intro: List<Marker> = emptyList(),
	val credits: List<Marker> = emptyList(),
	val chapters: List<Chapter> = emptyList(),
	val isAnime: Boolean = false,
	/** The quality the route was decided at: kbit/s, 0 for Original. */
	val quality: Int = 0,
	/** Auto adapts the quality on this stream. */
	val adapts: Boolean = false,
)

@Serializable
data class PlayRoute(
	val kind: RouteKind,
	/** Why the file does not play as it is, in Core's words. */
	val reasons: List<String> = emptyList(),
	/** The audio stream the route plays. */
	val audio: Int? = null,
	val audioIsStandIn: Boolean = false,
	val reencodesVideo: Boolean = false,
	val maxVideoBitrateKbps: Int? = null,
	val videoResolution: String? = null,
	val subtitle: Delivery = Delivery(),
)

@Serializable
enum class RouteKind {
	/** The tablet reads the file as it is. */
	@SerialName("directPlay") DirectPlay,

	/** Core's remux, repackaging on the device. The bridge does not allow it on the tablet; the enum names every route Core has. */
	@SerialName("remux") Remux,

	/** The server converts. */
	@SerialName("serverTranscode") ServerTranscode,
}

/** How the chosen subtitle reaches the screen: none, sidecar, inStream, burnedIn or notShown. */
@Serializable
data class Delivery(val kind: String = "none", val track: Int? = null)

/** What the player opens: the file itself, or the server's HLS conversion, whose address carries the token. */
@Serializable
data class PlayStream(
	val kind: StreamKind,
	val url: String,
	val headers: Map<String, String> = emptyMap(),
	/** The server's conversion, to stop when playback ends. */
	val session: String? = null,
	/** Where in the title the stream starts, in seconds. */
	val offset: Double = 0.0,
) {
	@Serializable
	enum class StreamKind {
		@SerialName("file") File,
		@SerialName("hls") Hls,
	}
}

/** A timed read of the file for the link's speed: the address without a token, and the limits Core sets. */
@Serializable
data class LinkProbe(
	val url: String,
	val headers: Map<String, String> = emptyMap(),
	val maxBytes: Int,
	val windowSeconds: Double,
	val timeoutSeconds: Double,
)

/** An intro or credits span, in milliseconds. */
@Serializable
data class Marker(val start: Int, val end: Int, val isFinal: Boolean = false)

@Serializable
data class Chapter(val title: String? = null, val start: Int, val end: Int)

/** The items on either side of the one playing in the play queue. */
@Serializable
data class Neighbors(val next: PlexItem? = null, val previous: PlexItem? = null)

@Serializable
private data class Took(val took: Boolean)

/** Auto's answer to a look: the rung to move to, if any, and what it knows of the link. */
@Serializable
data class QualityMove(val rung: Int? = null, val estimateKbps: Int? = null, val belowFloor: Boolean = false)

/** The streaming qualities a profile can pick. Raw values are Core's `QualitySetting`. */
object QualityPreset {
	const val AUTO = -1
	const val ORIGINAL = 0

	/** Auto, Original, then the converted rungs. */
	val all = listOf(AUTO, ORIGINAL, 20_000, 12_000, 8_000, 4_000, 2_000)

	fun title(quality: Int): String = when (quality) {
		AUTO -> "Auto"
		ORIGINAL -> "Original"
		20_000, 12_000, 8_000 -> "${quality / 1000} Mbps 1080p"
		else -> "${quality / 1000} Mbps 720p"
	}
}

/**
 * Opens [request.ratingKey]'s item for playing and says how. The first call of an item may come back with a
 * [PlayPlan.probe] and no stream: read it and call again with the [StartRequest.probe] reading.
 */
suspend fun MarqueeCore.startPlay(server: PlexServer, remembered: String?, request: StartRequest): PlayPlan =
	call("play.start", serverParams(server, remembered) {
		CoreJson.encodeToJsonElement(StartRequest.serializer(), request).jsonObject.forEach { (name, value) -> put(name, value) }
	})

/** The items next to the one [session] plays, for Next and Up Next. */
suspend fun MarqueeCore.playNeighbors(server: PlexServer, remembered: String?, session: String): Neighbors =
	call("play.queue", serverParams(server, remembered) { put("session", session) })

/** Tells the server where playback is. [state] is `playing`, `paused`, `buffering` or `stopped`; false when Core held the report back. */
suspend fun MarqueeCore.playReport(
	server: PlexServer, remembered: String?, session: String, state: String, timeMs: Int, durationMs: Int,
): Boolean = call<Took>("play.report", serverParams(server, remembered) {
	put("session", session)
	put("state", state)
	put("timeMs", timeMs)
	put("durationMs", durationMs)
}).took

/** Reports the stop and stops the server's conversion. [close] ends the session with the screen. */
suspend fun MarqueeCore.playStop(
	server: PlexServer, remembered: String?, session: String, timeMs: Int, durationMs: Int, close: Boolean,
) {
	call<JsonObject>("play.stop", serverParams(server, remembered) {
		put("session", session)
		put("timeMs", timeMs)
		put("durationMs", durationMs)
		put("close", close)
	})
}

/** Hands Auto what the last two seconds of the stream measured. */
suspend fun MarqueeCore.playLook(
	server: PlexServer, remembered: String?, session: String, now: Double,
	throughputKbps: Int?, transferSeconds: Double, bufferSeconds: Double?, waitingSeconds: Double,
): QualityMove = call("play.look", serverParams(server, remembered) {
	put("session", session)
	put("now", now)
	if (throughputKbps != null) put("throughputKbps", throughputKbps)
	put("transferSeconds", transferSeconds)
	if (bufferSeconds != null) put("bufferSeconds", bufferSeconds)
	put("waitingSeconds", waitingSeconds)
})

/** Notes the speed of the link that playing the file itself measured, for the next item's route. */
suspend fun MarqueeCore.playLink(server: PlexServer, remembered: String?, session: String, kbps: Int, network: Int, now: Double) {
	call<JsonObject>("play.link", serverParams(server, remembered) {
		put("session", session)
		put("kbps", kbps)
		put("network", network)
		put("now", now)
	})
}

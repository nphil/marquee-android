package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
internal data class StartRequest(
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
	/** Auto moved to this rung: a restart after [playLook] named it, by its place in Auto's ladder (the bridge keeps the ladder). */
	val move: Int? = null,
	/** The read [PlayPlan.probe] asked for. */
	val probe: Reading? = null,
	/** Hand picks: an audio stream, the subtitles menu's choice, and what the profile remembers of the title (Core's JSON, kept as it came). */
	val audio: Int? = null,
	val subtitle: SubtitlePick? = null,
	val memory: JsonElement? = null,
	val recordingMediaID: Int? = null,
	/** The saved HomeLabber link, when its plugin is on: a title it has measured streams by its own curve under Auto. Never log it. */
	val homelabber: HomeLabberLink? = null,
	/** The device's class, for HomeLabber's phone or tablet scores. */
	val screen: ScreenClass? = null,
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
	val tracks: PlayTracks = PlayTracks(),
	val isAnime: Boolean = false,
	/** The quality the route was decided at: kbit/s, 0 for Original. */
	val quality: Int = 0,
	/** What Auto streams at, worded for the screen: a preset's name or a step of the title's measured curve such as "3.2 Mbps 720p". */
	val qualityTitle: String? = null,
	/** Auto adapts the quality on this stream. */
	val adapts: Boolean = false,
	/** What the first-start badge shows for the stream that plays. */
	val badge: FormatBadge = FormatBadge(),
	/** The server's scene-thumbnail index (BIF) of the part, when it has one. */
	val thumbnails: ThumbnailSource? = null,
)

/** Where to read a part's BIF index: the address and what authorizes the request. Never log it. */
@Serializable
data class ThumbnailSource(val url: String, val headers: Map<String, String> = emptyMap())

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

/**
 * What the first-start badge says about the stream that plays: the picture size, the high dynamic range kind and the
 * surround sound. Each is null when unknown or when it does not apply to the route, so nothing is made up.
 */
@Serializable
data class FormatBadge(
	/** `SD`, `720p`, `1080p`, `1440p`, `4K` or `8K`. */
	val resolution: String? = null,
	val range: BadgeRange? = null,
	val sound: BadgeSound? = null,
) {
	val isEmpty: Boolean get() = resolution == null && range == null && sound == null

	/** "4K, Dolby Vision, Dolby Atmos", for TalkBack. */
	val spoken: String get() = listOfNotNull(resolution, range?.words, sound?.words).joinToString(", ")
}

@Serializable
enum class BadgeRange(val words: String) {
	@SerialName("hdr10") Hdr10("HDR10"),
	@SerialName("hdr10Plus") Hdr10Plus("HDR10+"),
	@SerialName("hlg") Hlg("HLG"),
	@SerialName("dolbyVision") DolbyVision("Dolby Vision"),
}

@Serializable
enum class BadgeSound(val words: String) {
	@SerialName("atmos") Atmos("Dolby Atmos"),
	@SerialName("surround51") Surround51("5.1"),
	@SerialName("surround71") Surround71("7.1"),
}

/** How the chosen subtitle reaches the screen; only a Chromecast route can ask the server to burn it in. */
@Serializable
data class Delivery(val kind: String = DELIVERY_NONE, val track: Int? = null)

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

/** The subtitles menu's choice: off, automatic (what the rules pick), or one track by its stream id. */
@Serializable
data class SubtitlePick(val kind: String, val id: Int? = null) {
	companion object {
		val Off = SubtitlePick("off")
		val Automatic = SubtitlePick("automatic")
		fun track(id: Int) = SubtitlePick("track", id)
	}
}

/** The playing file's tracks as the Tracks menu lists them, named by Core. */
@Serializable
data class PlayTracks(
	val audio: List<AudioOption> = emptyList(),
	/** Every subtitle in file order, separate files last, so the embedded ones line up with the player's own text tracks. */
	val subtitles: List<SubtitleOption> = emptyList(),
	/** The track On puts on screen; null when the file has no English text. */
	val automatic: Int? = null,
	/** Sent with a subtitle download. */
	val headers: Map<String, String> = emptyMap(),
) {
	/** What a menu lists: a separate file that replaces an embedded copy hides it. */
	val menuSubtitles: List<SubtitleOption> get() = subtitles.filterNot { it.isHidden }
}

@Serializable
data class AudioOption(val id: Int, val label: String, val language: String? = null, val isDefault: Boolean = false)

@Serializable
data class SubtitleOption(
	val id: Int,
	val label: String,
	val language: String? = null,
	val isDefault: Boolean = false,
	val isExternal: Boolean = false,
	val isHidden: Boolean = false,
	/** How it shows on this route if picked: `sidecar` (a file the app loads), `inStream` (inside the played file) or `notShown`. */
	val delivery: String = DELIVERY_NOT_SHOWN,
	/** Where a separate text file downloads as SRT; null for a track inside the file and for pictures. */
	val url: String? = null,
)

const val DELIVERY_NONE = "none"
const val DELIVERY_SIDECAR = "sidecar"
const val DELIVERY_IN_STREAM = "inStream"
internal const val DELIVERY_BURNED_IN = "burnedIn"
const val DELIVERY_NOT_SHOWN = "notShown"

/** The items on either side of the one playing in the play queue. */
@Serializable
data class Neighbors(val next: PlexItem? = null, val previous: PlexItem? = null)

@Serializable
private data class Took(val took: Boolean)

/** Auto's answer to a look: the rung to move to (its place in Auto's ladder), if any, and what it knows of the link. */
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
internal suspend fun MarqueeCore.startPlay(server: PlexServer, remembered: String?, request: StartRequest): PlayPlan =
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

/** Releases the server stream. Dropping an uncommitted or transferred stream doesn't change the timeline. */
internal suspend fun MarqueeCore.playStop(session: String, timeMs: Int, durationMs: Int, close: Boolean, report: Boolean = true) {
	call<JsonObject>("play.stop", buildJsonObject {
		put("session", session)
		put("timeMs", timeMs)
		put("durationMs", durationMs)
		put("close", close)
		if (!report) put("report", false)
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

/**
 * Folds a hand pick into what the profile remembers of the title ([memory], null when nothing yet) and returns what to
 * keep. [audio] is a stream id; [subtitle] is the menu's choice.
 */
suspend fun MarqueeCore.playRemember(
	server: PlexServer, remembered: String?, session: String, audio: Int?, subtitle: SubtitlePick?, memory: JsonElement?,
): JsonElement = call("play.remember", serverParams(server, remembered) {
	put("session", session)
	if (audio != null) put("audio", audio)
	if (subtitle != null) put("subtitle", CoreJson.encodeToJsonElement(SubtitlePick.serializer(), subtitle))
	if (memory != null) put("memory", memory)
})

/** What the profile's autoplay rule does when an episode reaches its credits marker. */
@Serializable
enum class AtCredits {
	/** Up Next is offered and waits for a tap. */
	@SerialName("offer") Card,

	/** Up Next counts down, then the next episode plays. */
	@SerialName("countdown") Countdown,

	/** The next episode starts at once, with no card. */
	@SerialName("playNow") PlayNow,
}

/** What Up Next does at the credits when autoplay is on (Core's `CreditsAutoplay`). */
@Serializable
enum class CreditsAutoplay {
	@SerialName("countdown") Countdown,
	@SerialName("skipInstantly") SkipInstantly,
}

/** How the picture's taps skip (Core's `SkipTapMode`). */
@Serializable
enum class SkipTapMode {
	@SerialName("doubleTap") DoubleTap,
	@SerialName("chained") Chained,
}

/** What autoplay does at an episode's credits marker and when it ends (Core's `AutoplayNext`). */
@Serializable
data class AutoplayPlan(
	val atCredits: AtCredits,
	/** The countdown's length, for [AtCredits.Countdown]. */
	val seconds: Int? = null,
	/** The finished episode goes on to the next: autoplay is on and the credits were not chosen to be watched. */
	val playsNextAtEnd: Boolean,
)

suspend fun MarqueeCore.playAutoplay(enabled: Boolean, credits: CreditsAutoplay, delay: Int, creditsWatched: Boolean): AutoplayPlan =
	call("play.autoplay", buildJsonObject {
		put("enabled", enabled)
		put("credits", CoreJson.encodeToJsonElement(CreditsAutoplay.serializer(), credits))
		put("delay", delay)
		put("creditsWatched", creditsWatched)
	})

/** Core's verdict on a skip past the intro. */
@Serializable
enum class SkipVerdict {
	/** No skip is being confirmed. */
	@SerialName("none") None,
	@SerialName("waiting") Waiting,
	@SerialName("landed") Landed,

	/** The skip did not land: seek there once more. */
	@SerialName("retry") Retry,
	@SerialName("missed") Missed,
}

@Serializable
data class SkipAnswer(val verdict: SkipVerdict, val retried: Boolean)

/** Starts confirming a skip past the intro, to [target] seconds. */
suspend fun MarqueeCore.playSkipBegin(session: String, target: Double) {
	call<JsonObject>("play.skip.begin", buildJsonObject {
		put("session", session)
		put("target", target)
	})
}

/** Judges where playback is against the skip being confirmed; [advanced] is the seconds played since the last look. */
suspend fun MarqueeCore.playSkipCheck(session: String, playhead: Double, advanced: Double, pending: Boolean): SkipAnswer =
	call("play.skip.check", buildJsonObject {
		put("session", session)
		put("playhead", playhead)
		put("advanced", advanced)
		put("pending", pending)
	})

/** What a tap on the picture does. */
@Serializable
enum class TapAction {
	@SerialName("skip") Skip,

	/** A first tap: a second within the window skips; otherwise the controls show or hide. */
	@SerialName("single") Single,
	@SerialName("controls") Controls,
}

@Serializable
enum class SkipSide {
	@SerialName("back") Back,
	@SerialName("forward") Forward,
}

@Serializable
data class TapAnswer(
	val action: TapAction,
	val side: SkipSide? = null,
	/** For [TapAction.Single]: the seconds to wait for a second tap. */
	val window: Double? = null,
)

/** Tells a double-tap from a single tap at [x] of [width], at [at] seconds on a steady clock. */
suspend fun MarqueeCore.playTap(session: String, mode: SkipTapMode, x: Double, width: Double, at: Double): TapAnswer =
	call("play.tap", buildJsonObject {
		put("session", session)
		put("mode", CoreJson.encodeToJsonElement(SkipTapMode.serializer(), mode))
		put("x", x)
		put("width", width)
		put("at", at)
	})

/** Forgets a pending tap and any chain, for a tap that must only bring the controls. */
suspend fun MarqueeCore.playTapReset(session: String) {
	call<JsonObject>("play.tap.reset", buildJsonObject { put("session", session) })
}

/** The server's own account of the conversion that plays; [active] is false when it has none or does not answer. */
@Serializable
data class TranscodeState(
	val active: Boolean,
	/** "copy" or "transcode"; for [subtitle] also "burn". */
	val video: String? = null,
	val audio: String? = null,
	val subtitle: String? = null,
	/** Encoding speed as a multiple of real time; below 1 the player outruns it. */
	val speed: Double? = null,
	val throttled: Boolean? = null,
	val fullPipeline: Boolean? = null,
	val decoding: String? = null,
	val encoding: String? = null,
)

suspend fun MarqueeCore.playTranscode(session: String): TranscodeState =
	call("play.transcode", buildJsonObject { put("session", session) })

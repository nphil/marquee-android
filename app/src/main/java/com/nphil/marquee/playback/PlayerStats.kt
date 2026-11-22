package com.nphil.marquee.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import com.nphil.marquee.core.DELIVERY_IN_STREAM
import com.nphil.marquee.core.DELIVERY_NOT_SHOWN
import com.nphil.marquee.core.DELIVERY_SIDECAR
import com.nphil.marquee.core.Delivery
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayRoute
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.PlexStream
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.StreamKind
import com.nphil.marquee.core.TranscodeState
import java.util.Locale

// How the player says what plays and how it keeps up: Playback Info, the stats overlay and the text Copy puts on the
// clipboard. Codec names appear here and nowhere else. No address or token is ever a line.

/** One line of Playback Info or the stats overlay. */
@Immutable
data class StatLine(val label: String, val value: String)

/** A titled group of lines of Playback Info. */
@Immutable
data class StatSection(val title: String, val lines: List<StatLine>)

/** The stats overlay's two columns: what plays, and how playback keeps up. */
@Immutable
data class Stats(val stream: List<StatLine>, val health: List<StatLine>) {
	val all: List<StatLine> get() = stream + health
}

/** What the running engine has measured. Zeros and nulls where it has not yet. */
internal data class EngineReading(
	val videoDecoder: String? = null,
	val displayWidth: Int = 0,
	val displayHeight: Int = 0,
	/** The player's own estimate of what the link carries, in bits per second. */
	val networkBps: Long = 0,
	/** The video stream's declared rate, in bits per second. */
	val streamBps: Int = 0,
	val droppedFrames: Int = 0,
	val stalls: Int = 0,
	val bufferSeconds: Double = 0.0,
	val errors: Int = 0,
	val lastError: String? = null,
)

internal fun PlayPlan.part(): PlexPart? = item.media.getOrNull(mediaIndex)?.parts?.firstOrNull()

internal fun PlayPlan.videoStream(): PlexStream? = part()?.streams?.firstOrNull { it.kind == StreamKind.Video }

/** The audio stream the route plays. */
internal fun PlayPlan.audioStream(): PlexStream? = route.audio?.let { id -> part()?.streams?.firstOrNull { it.id == id } }

/** The route in a few words. */
internal fun PlayRoute.title(): String = when (kind) {
	RouteKind.DirectPlay -> "Direct Play"
	RouteKind.Remux -> "Repackaged on the tablet"
	RouteKind.ServerTranscode -> if (reencodesVideo) "Converted by the server" else "Repackaged by the server"
}

/** How the chosen subtitle reaches the screen, in words; null when none is chosen. */
internal fun Delivery.words(): String? = when (kind) {
	DELIVERY_SIDECAR -> "Drawn by Marquee"
	DELIVERY_IN_STREAM -> "In the video stream"
	"burnedIn" -> "Burned in by the server"
	DELIVERY_NOT_SHOWN -> "Not shown"
	else -> null
}

/** "HEVC Main 10, level 5.1". */
internal fun PlexStream.formatSummary(): String {
	val profileWords = profile?.split(' ')?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
	var text = listOfNotNull(codec?.uppercase(Locale.ROOT) ?: "Unknown", profileWords).joinToString(" ")
	// Plex passes the stream's level number through: 51 for H.264 5.1, 153 (30 x 5.1) for HEVC.
	if (level != null) text += ", level ${trimmed(level / (if (codec == "hevc") 30.0 else 10.0), 1)}"
	return text
}

/** "3840 × 2160, 10-bit". */
internal fun PlexStream.sizeSummary(): String? {
	val across = width ?: return null
	val down = height ?: return null
	return listOfNotNull("$across × $down", bitDepth?.let { "$it-bit" }).joinToString(", ")
}

internal fun PlexStream.dynamicRange(): String {
	dolbyVision?.let { return "Dolby Vision profile ${it.profile}" }
	return when (colorTrc) {
		"smpte2084" -> "HDR10"
		"arib-std-b67" -> "HLG"
		else -> "SDR"
	}
}

internal fun channelWords(channels: Int): String = when (channels) {
	1 -> "Mono"
	2 -> "Stereo"
	6 -> "5.1"
	8 -> "7.1"
	else -> "$channels channels"
}

/** [bitsPerSecond] as "12.5 Mbps". */
internal fun mbps(bitsPerSecond: Double): String = String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000)

/** [bytes] as "4.21 GB", "812 MB" or "64 KB", with decimal units as a file manager shows them. */
internal fun fileSize(bytes: Long): String = when {
	bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
	bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1e6)
	else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1e3)
}

/** [value] with up to [digits] decimals and no trailing zeros: 5.1, 4, 23.976. */
internal fun trimmed(value: Double, digits: Int): String =
	String.format(Locale.ROOT, "%.${digits}f", value).trimEnd('0').trimEnd('.')

/** How long the start took to reach each step, in seconds; null for a step that has not been reached. */
internal fun startSummary(planMs: Long?, readyMs: Long?): String {
	val steps = listOfNotNull(planMs?.let { "plan ${trimmed(it / 1000.0, 2)} s" }, readyMs?.let { "ready ${trimmed(it / 1000.0, 2)} s" })
	return if (steps.isEmpty()) "waiting" else steps.joinToString(" · ")
}

/** Software decoders are Google's and the codec libraries'; every other name belongs to the chip. */
internal fun isSoftwareDecoder(name: String): Boolean {
	val lower = name.lowercase(Locale.ROOT)
	return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("c2.google.") || lower.contains("ffmpeg")
}

/** How the title plays and why, and the file's tracks: the sections of Playback Info. */
internal fun infoSections(plan: PlayPlan, fallback: String?, audioLabel: String?, subtitleLabel: String?): List<StatSection> {
	val route = plan.route
	val media = plan.item.media.getOrNull(plan.mediaIndex)
	val part = plan.part()
	return listOfNotNull(
		StatSection(
			"Playing",
			listOfNotNull(
				StatLine("Route", route.title()),
				if (route.reasons.isEmpty()) null else StatLine("Why", route.reasons.joinToString("\n")),
				fallback?.let { StatLine("Fallback", it) },
			),
		),
		plan.videoStream()?.let { video ->
			StatSection(
				"Video",
				listOfNotNull(
					StatLine("Format", video.formatSummary()),
					video.sizeSummary()?.let { StatLine("Size", it) },
					video.frameRate?.let { StatLine("Frame Rate", "${trimmed(it, 3)} fps") },
					StatLine("Dynamic Range", video.dynamicRange()),
					if (route.reencodesVideo) StatLine("Delivery", "Converted by the server") else null,
				),
			)
		},
		plan.audioStream()?.let { audio ->
			val format = listOfNotNull(audio.codec?.uppercase(Locale.ROOT), audio.channels?.let(::channelWords)).joinToString(", ")
			StatSection(
				"Audio",
				listOfNotNull(
					audioLabel?.let { StatLine("Track", it) },
					if (format.isEmpty()) null else StatLine("Format", format),
					if (route.audioIsStandIn) StatLine("Note", "Stands in for a track the tablet can't play") else null,
				),
			)
		},
		StatSection(
			"Subtitles",
			listOfNotNull(StatLine("Track", subtitleLabel ?: "Off"), route.subtitle.words()?.let { StatLine("Delivery", it) }),
		),
		media?.let {
			StatSection(
				"File",
				listOfNotNull(
					(part?.container ?: it.container)?.let { container -> StatLine("Container", container.uppercase(Locale.ROOT)) },
					it.bitrate?.let { kbps -> StatLine("Bitrate", mbps(kbps * 1000.0)) },
					part?.size?.let { size -> StatLine("Size", fileSize(size)) },
				),
			)
		},
	).filter { it.lines.isNotEmpty() }
}

/** What plays, and why: the left column of the stats overlay. */
internal fun streamLines(plan: PlayPlan, fallback: String?, reading: EngineReading): List<StatLine> = buildList {
	val route = plan.route
	add(StatLine("Route", route.title()))
	if (route.reasons.isNotEmpty()) add(StatLine("Why", route.reasons.joinToString(", ")))
	fallback?.let { add(StatLine("Fallback", it)) }
	if (plan.adapts) {
		add(StatLine("Auto", QualityPreset.title(plan.quality)))
	} else if (plan.quality != QualityPreset.ORIGINAL) {
		add(StatLine("Quality", QualityPreset.title(plan.quality)))
	}
	val media = plan.item.media.getOrNull(plan.mediaIndex)
	val part = plan.part()
	val file = listOfNotNull(
		(part?.container ?: media?.container)?.uppercase(Locale.ROOT),
		media?.bitrate?.let { mbps(it * 1000.0) },
		part?.size?.let(::fileSize),
	)
	if (file.isNotEmpty()) add(StatLine("File", file.joinToString(" · ")))
	plan.videoStream()?.let { video ->
		val rate = video.frameRate?.let { "${trimmed(it, 3)} fps" }
		add(StatLine("Video", listOfNotNull(video.formatSummary(), video.sizeSummary(), rate).joinToString(" · ")))
		val picture = mutableListOf(video.dynamicRange())
		val decoder = reading.videoDecoder
		if (route.reencodesVideo) {
			picture += "converted by the server"
		} else if (decoder != null) {
			picture += "$decoder (${if (isSoftwareDecoder(decoder)) "software" else "hardware"})"
		}
		add(StatLine("Picture", picture.joinToString(" · ")))
	}
	plan.audioStream()?.let { audio ->
		add(StatLine("Audio", listOfNotNull(audio.codec?.uppercase(Locale.ROOT), audio.channels?.let { "$it ch" }).joinToString(" ")))
	}
	add(StatLine("Subtitles", route.subtitle.words() ?: "Off"))
}

/** How playback keeps up, and how the tablet is coping: the right column. */
internal fun healthLines(start: String, reading: EngineReading, transcode: String?, system: List<StatLine>): List<StatLine> = buildList {
	add(StatLine("Start", start))
	if (reading.displayWidth > 0) add(StatLine("Display", "${reading.displayWidth} × ${reading.displayHeight}"))
	var network = if (reading.networkBps > 0) mbps(reading.networkBps.toDouble()) else "–"
	if (reading.streamBps > 0) network += " · stream ${mbps(reading.streamBps.toDouble())}"
	add(StatLine("Network", network))
	add(StatLine("Frames", "${reading.droppedFrames} dropped · ${reading.stalls} stalls"))
	add(StatLine("Buffer", String.format(Locale.ROOT, "%.1f s ahead", reading.bufferSeconds)))
	if (reading.errors > 0) add(StatLine("Errors", "${reading.errors} · last ${reading.lastError}"))
	transcode?.let { add(StatLine("Transcode", it)) }
	addAll(system)
}

/** The tablet's heat and memory. */
internal fun systemLines(app: Context): List<StatLine> {
	val power = app.getSystemService(PowerManager::class.java)
	var heat = when (power.currentThermalStatus) {
		PowerManager.THERMAL_STATUS_NONE -> "Nominal"
		PowerManager.THERMAL_STATUS_LIGHT -> "Light"
		PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
		PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
		PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
		PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
		else -> "Shutdown"
	}
	if (power.isPowerSaveMode) heat += " · Power Saver"
	val system = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
	val runtime = Runtime.getRuntime()
	val used = runtime.totalMemory() - runtime.freeMemory() + Debug.getNativeHeapAllocatedSize()
	return listOf(StatLine("Heat", heat), StatLine("Memory", "${fileSize(used)} used · ${fileSize(system.availMem)} free"))
}

/** The overlay's lines as text for a bug report: the app, the title, then every line. */
internal fun reportText(version: String, title: String, lines: List<StatLine>): String =
	(listOf("Marquee $version", title, "") + lines.map { "${it.label}: ${it.value}" }).joinToString("\n")

/** The server's account of its conversion in a line: what it does to each stream, how fast, and on what; null when it has none. */
internal fun TranscodeState.words(): String? {
	if (!active) return null
	val hardware = when {
		fullPipeline == true -> "hardware"
		decoding != null || encoding != null ->
			"hardware ${listOfNotNull(decoding?.let { "decode $it" }, encoding?.let { "encode $it" }).joinToString(", ")}"
		else -> "software"
	}
	return listOfNotNull(
		video?.let { "video $it" },
		audio?.let { "audio $it" },
		subtitle?.let { "subtitles $it" },
		speed?.let { "${trimmed(it, 1)}× real time" },
		if (throttled == true) "throttled" else null,
		hardware,
	).joinToString(" · ")
}

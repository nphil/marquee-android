package com.nphil.marquee.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Plex items in the JSON the bridge sends (bridge/Sources/MarqueeBridge/ItemWire.swift): PlexMetadata's own field
// names, with what an item lacks left out, so every field but the identifying ones has a default. Dates are whole
// seconds since 1970. The words and artwork choices at the end every browse screen shares; they follow
// core/App/Browse/ItemText.swift.

@Serializable
enum class MediaType {
	@SerialName("movie") Movie,
	@SerialName("show") Show,
	@SerialName("season") Season,
	@SerialName("episode") Episode,
	@SerialName("collection") Collection,
	@SerialName("clip") Clip,
	@SerialName("playlist") Playlist,
	@SerialName("unknown") Unknown,
}

/** A movie, show, season, episode, collection or extra. */
@Immutable
@Serializable
data class PlexItem(
	val ratingKey: String,
	/** Path of the item, or of its children for shows and seasons. */
	val key: String = "",
	val guid: String? = null,
	val type: MediaType = MediaType.Unknown,
	/** What a collection holds (`movie` or `show`), or an extra's kind (`trailer`). */
	val subtype: String? = null,
	/** An extra's kind: 1 trailer, 2 deleted scene, 3 interview, 5 behind the scenes, 6 scene, 10 featurette, 11 short. */
	val extraType: Int? = null,
	/** The main trailer's key, "/library/metadata/1073", on a title that has one. */
	val primaryExtraKey: String? = null,
	val title: String = "",
	val titleSort: String? = null,
	val originalTitle: String? = null,
	val summary: String = "",
	val tagline: String? = null,
	val year: Int? = null,
	/** Episode number, or season number for a season. */
	val index: Int? = null,
	/** Season number of an episode; 0 is Specials. */
	val parentIndex: Int? = null,
	val parentRatingKey: String? = null,
	val parentGuid: String? = null,
	val parentTitle: String? = null,
	val grandparentRatingKey: String? = null,
	val grandparentGuid: String? = null,
	val grandparentTitle: String? = null,
	val thumb: String? = null,
	val art: String? = null,
	val parentThumb: String? = null,
	val grandparentThumb: String? = null,
	val grandparentArt: String? = null,
	/** A show's theme tune, on a show that has one. */
	val theme: String? = null,
	/** A season's show's theme tune. */
	val parentTheme: String? = null,
	/** Milliseconds; 0 when unknown. */
	val duration: Int = 0,
	/** Resume point in milliseconds; 0 when not started. */
	val viewOffset: Int = 0,
	val viewCount: Int = 0,
	/** Episodes in a show or season. */
	val leafCount: Int? = null,
	val viewedLeafCount: Int? = null,
	val childCount: Int? = null,
	val addedAt: Long? = null,
	val lastViewedAt: Long? = null,
	/** Release date as Plex writes it, `yyyy-MM-dd`. */
	val originallyAvailableAt: String? = null,
	val contentRating: String? = null,
	val studio: String? = null,
	val rating: Double? = null,
	val audienceRating: Double? = null,
	val librarySectionID: Int? = null,
	val librarySectionTitle: String? = null,
	val playQueueItemID: Int? = null,
	val media: List<PlexMedia> = emptyList(),
	val roles: List<PlexRole> = emptyList(),
	val genres: List<String> = emptyList(),
	val directors: List<String> = emptyList(),
	val writers: List<String> = emptyList(),
	val images: List<PlexImage> = emptyList(),
	/** The episode to play next, on a show read on its own. */
	val onDeck: PlexItem? = null,
	/** On a Continue Watching card the app made rather than Plex listed: when its show was last viewed. */
	val rewatchedAt: Long? = null,
	/** Watched: a played movie or episode, or a show or season with no episode left to watch. */
	val isWatched: Boolean = false,
	/** Episodes left to watch in a show or season. */
	val unwatchedLeafCount: Int? = null,
	/** The show this item belongs to: itself for a show, the parent for a season, the grandparent for an episode. */
	val showRatingKey: String? = null,
)

/** One version of an item: a file with its container and codecs. */
@Immutable
@Serializable
data class PlexMedia(
	val id: Int = 0,
	val duration: Int = 0,
	/** Kilobits per second. */
	val bitrate: Int? = null,
	val width: Int? = null,
	val height: Int? = null,
	val aspectRatio: Double? = null,
	val audioChannels: Int? = null,
	val audioCodec: String? = null,
	val videoCodec: String? = null,
	/** `"1080"`, `"4k"`, `"sd"`. */
	val videoResolution: String? = null,
	val container: String? = null,
	val videoFrameRate: String? = null,
	val videoProfile: String? = null,
	val origin: String? = null,
	val mediaGrabStatus: String? = null,
	val parts: List<PlexPart> = emptyList(),
)

/** One file of a version. Streams are only listed on an item's own request. */
@Immutable
@Serializable
data class PlexPart(
	val id: Int = 0,
	/** Download path, e.g. `/library/parts/12/1699999999/file.mkv`. */
	val key: String = "",
	val duration: Int = 0,
	/** Path on the server, for display only. */
	val file: String? = null,
	/** Bytes. */
	val size: Long? = null,
	val container: String? = null,
	val streams: List<PlexStream> = emptyList(),
	/** The audio and subtitle menu Core builds for this file, named by its labeller; null when it has neither. */
	val tracks: PlexTracks? = null,
	/** The server made scene thumbnails for the scrubber. */
	val hasSceneThumbnails: Boolean = false,
)

/** A video, audio or subtitle stream of a part, embedded or (for subtitles) a separate file. */
@Immutable
@Serializable
data class PlexStream(
	val id: Int = 0,
	/** Plex's number: 1 video, 2 audio, 3 subtitle. */
	val streamType: Int = 0,
	val index: Int? = null,
	/** Download path of a separate subtitle file. */
	val key: String? = null,
	val codec: String? = null,
	/** Subtitle format: `srt`, `ass`, `pgs`. */
	val format: String? = null,
	val language: String? = null,
	/** ISO 639-2, e.g. `eng`. */
	val languageCode: String? = null,
	/** BCP 47, e.g. `en-GB`. */
	val languageTag: String? = null,
	val title: String? = null,
	val displayTitle: String? = null,
	val extendedDisplayTitle: String? = null,
	val isDefault: Boolean = false,
	/** The stream Plex selected for this user and item. */
	val isSelected: Boolean = false,
	val isForced: Boolean = false,
	val isHearingImpaired: Boolean = false,
	/** An audio-description track. */
	val isVisualImpaired: Boolean = false,
	val isEmbeddedInVideo: Boolean = false,
	val canNormalizeLoudness: Boolean = false,
	val channels: Int? = null,
	val audioChannelLayout: String? = null,
	val samplingRate: Int? = null,
	val bitrate: Int? = null,
	val profile: String? = null,
	val level: Int? = null,
	val bitDepth: Int? = null,
	val chromaSubsampling: String? = null,
	val colorPrimaries: String? = null,
	val colorTrc: String? = null,
	val colorSpace: String? = null,
	val frameRate: Double? = null,
	val width: Int? = null,
	val height: Int? = null,
	val dolbyVision: DolbyVision? = null,
) {
	val kind: StreamKind
		get() = when (streamType) {
			1 -> StreamKind.Video
			2 -> StreamKind.Audio
			3 -> StreamKind.Subtitle
			else -> StreamKind.Other
		}

	/** A subtitle in its own file next to the video. */
	val isExternal: Boolean get() = key != null
}

enum class StreamKind { Video, Audio, Subtitle, Other }

/** The tracks a menu offers for one file. Subtitles are the visible ones: a separate file hides its embedded twin. */
@Immutable
@Serializable
data class PlexTracks(
	val audio: List<AudioChoice> = emptyList(),
	val subtitles: List<SubtitleChoice> = emptyList(),
)

@Immutable
@Serializable
data class AudioChoice(
	val id: Int,
	/** "English", "English (commentary)", "Spanish (Latin America)". */
	val label: String,
	/** Lower case. */
	val codec: String = "",
	val channels: Int? = null,
	val title: String? = null,
	val isDefault: Boolean = false,
	/** "Audio description". */
	val badges: List<String> = emptyList(),
)

@Immutable
@Serializable
data class SubtitleChoice(
	val id: Int,
	/** "English", "English (on-screen text)". */
	val label: String,
	val codec: String = "",
	val isExternal: Boolean = false,
	/** "PGS", "VOBSUB" or "DVB" for picture-based subtitles. */
	val imageFormat: String? = null,
	/** "Captions". */
	val badges: List<String> = emptyList(),
)

@Immutable
@Serializable
data class DolbyVision(
	val profile: Int,
	val level: Int? = null,
	/** Base-layer compatibility: 1 HDR10, 2 SDR, 4 HLG, 6 HDR10 (profile 7), 0 none. */
	val baseLayerCompatibility: Int? = null,
	val hasEnhancementLayer: Boolean = false,
)

/** A cast member. */
@Immutable
@Serializable
data class PlexRole(
	/** The person's id, for their titles; null on trimmed lists such as search rows. */
	val id: String? = null,
	val name: String,
	val character: String? = null,
	val thumb: String? = null,
)

/** Artwork beyond `thumb` and `art`. */
@Immutable
@Serializable
data class PlexImage(
	/** `coverPoster`, `background`, `snapshot`, `clearLogo`. */
	val type: String,
	val url: String,
)

/** A row of items: a hub of Home, a search group, related titles. */
@Immutable
@Serializable
data class PlexHub(
	val identifier: String = "",
	val key: String? = null,
	val title: String = "",
	/** Item type of the row: `movie`, `show`, `episode`, `mixed`, `actor`. */
	val type: String? = null,
	/** `hero` marks the Continue Watching row. */
	val style: String? = null,
	val size: Int = 0,
	/** More items exist behind [key]. */
	val more: Boolean = false,
	val items: List<PlexItem> = emptyList(),
	/** People in a search group. */
	val directories: List<PlexDirectory> = emptyList(),
) {
	val id: String get() = identifier.ifEmpty { key ?: title }
}

/** A person, tag or library entry in a hub, such as a search group of people. */
@Immutable
@Serializable
data class PlexDirectory(
	val key: String = "",
	val title: String = "",
	/** Name of a person or tag. */
	val tag: String? = null,
	/** A person's or tag's id. */
	val id: String? = null,
	val type: String? = null,
	/** Items behind the entry. */
	val size: Int = 0,
	val thumb: String? = null,
	val art: String? = null,
)

// MARK: Words and artwork

/** Part-watched progress from 0 to 1; null when not started or finished. */
val PlexItem.progress: Float?
	get() = if (duration > 0 && viewOffset > 0 && viewOffset < duration) viewOffset.toFloat() / duration else null

/** Whole minutes left, rounded up; null when not started. */
val PlexItem.minutesLeft: Int?
	get() = if (progress == null) null else max(1, ceil((duration - viewOffset) / 60_000.0).toInt())

/** "S2 · E5" for an episode. */
val PlexItem.episodeCode: String?
	get() {
		val season = parentIndex
		val episode = index
		return if (type == MediaType.Episode && season != null && episode != null) "S$season · E$episode" else null
	}

/** The 2:3 artwork. An episode in a poster shelf shows its show's poster. */
val PlexItem.posterPath: String?
	get() = when (type) {
		MediaType.Episode -> grandparentThumb ?: parentThumb ?: thumb
		MediaType.Season -> thumb ?: parentThumb
		else -> thumb
	}

/** The 16:9 artwork for a card or a hero. */
val PlexItem.backdropPath: String?
	get() = art ?: grandparentArt

/** The title's clear logo, where Plex has one. */
val PlexItem.logoPath: String?
	get() = images.firstOrNull { it.type == "clearLogo" && it.url.isNotEmpty() }?.url

/** The name under a poster: an episode goes by its show. */
val PlexItem.posterTitle: String
	get() = if (type == MediaType.Episode) grandparentTitle ?: title else title

/** The unwatched-episode badge, or null for titles without episodes. */
val PlexItem.unwatchedBadge: Int?
	get() = if (type == MediaType.Show || type == MediaType.Season) unwatchedLeafCount?.takeIf { it > 0 } else null

/**
 * Whether a poster says "watched". Plex keeps a title's view count while it is watched again, so a part-watched movie or
 * episode that has one is in progress, not watched; the Continue Watching and On Deck hubs list exactly those. A show or
 * season is watched when Plex's episode counts say so, and a hub that lists one without them says nothing about it.
 */
val PlexItem.showsWatched: Boolean
	get() = isWatched && when (type) {
		MediaType.Collection, MediaType.Clip -> false
		MediaType.Show, MediaType.Season -> (leafCount ?: 0) > 0
		else -> progress == null
	}

/** What TalkBack reads for a poster or row (DESIGN §10): "Title, movie, 2019, 42 minutes left". */
val PlexItem.spokenSummary: String
	get() {
		val parts = mutableListOf<String>()
		when (type) {
			MediaType.Episode -> {
				parts += grandparentTitle.orEmpty()
				parts += parentIndex?.let { "season $it" }.orEmpty()
				parts += index?.let { "episode $it" }.orEmpty()
				parts += title
			}
			MediaType.Movie, MediaType.Show, MediaType.Collection -> {
				parts += title
				parts += type.name.lowercase()
			}
			MediaType.Season -> {
				parts += parentTitle.orEmpty()
				parts += title
			}
			else -> parts += title
		}
		year?.let { if (type != MediaType.Episode) parts += it.toString() }
		val left = minutesLeft
		val unwatched = unwatchedBadge
		when {
			left != null -> parts += ItemText.spokenLeft(left)
			unwatched != null -> parts += if (unwatched == 1) "1 unwatched episode" else "$unwatched unwatched episodes"
			showsWatched -> parts += "watched"
		}
		return parts.filter { it.isNotEmpty() }.joinToString(", ")
	}

/**
 * The meta line under a title (DESIGN §8.3): "2019 · 2 h 14 m · PG-13"; for a show its seasons, for an episode its
 * code and air date.
 */
val PlexItem.metaLine: String?
	get() {
		val parts = when (type) {
			MediaType.Episode ->
				listOf(episodeCode, ItemText.airDate(originallyAvailableAt), ItemText.runtime(duration), contentRating)
			MediaType.Show -> listOf(
				year?.toString(), childCount?.let { if (it == 1) "1 season" else "$it seasons" }, contentRating,
			)
			MediaType.Season -> listOf(parentTitle, leafCount?.let { if (it == 1) "1 episode" else "$it episodes" })
			else -> listOf(year?.toString(), ItemText.runtime(duration), contentRating)
		}
		return parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
	}

/** "Movie · 2019" under a search result (DESIGN §8.5). */
val PlexItem.kindLine: String
	get() = when (type) {
		MediaType.Episode -> listOfNotNull(grandparentTitle, episodeCode).joinToString(" · ")
		else -> {
			val kind = when (type) {
				MediaType.Movie -> "Movie"
				MediaType.Show -> "Show"
				MediaType.Season -> "Season"
				MediaType.Collection -> "Collection"
				else -> "Video"
			}
			listOfNotNull(kind, year?.toString()).joinToString(" · ")
		}
	}

/** The format chips (DESIGN §8.3): 4K, then Dolby Vision or HDR, then Atmos. */
val PlexMedia.formatChips: List<String>
	get() = buildList {
		if (videoResolution?.lowercase() == "4k") add("4K")
		dynamicRange?.let(::add)
		if (hasAtmos) add("Atmos")
	}

/** The video line under a poster: resolution and range, without sound or file details. */
internal val PlexMedia.videoMarks: String?
	get() {
		val quality = resolution
		val range = dynamicRange
		return if (quality == null) range else if (range == null) quality else "$quality · $range"
	}

/** "4K HDR · 12 GB", naming one version in the Version menu. */
val PlexMedia.versionLabel: String
	get() {
		val picture = listOfNotNull(resolution, dynamicRange).joinToString(" ")
		val bytes = parts.mapNotNull { it.size }.sum()
		val size = if (bytes > 0) ItemText.fileSize(bytes) else null
		val label = listOfNotNull(picture.ifEmpty { null }, size).joinToString(" · ")
		return label.ifEmpty { container?.uppercase() ?: "Version" }
	}

/** "4K", "1080p", "SD". */
private val PlexMedia.resolution: String?
	get() = videoResolution?.let { value ->
		when {
			value.lowercase() == "4k" -> "4K"
			value.toIntOrNull() != null -> "${value}p"
			else -> value.uppercase()
		}
	}

private val PlexMedia.dynamicRange: String?
	get() {
		val video = parts.firstOrNull()?.streams?.firstOrNull { it.kind == StreamKind.Video }
		return when {
			video?.dolbyVision != null -> "Dolby Vision"
			video?.colorTrc in HDR_TRANSFERS -> "HDR"
			else -> null
		}
	}

private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")

private val PlexMedia.hasAtmos: Boolean
	get() = parts.flatMap { it.streams }.any { stream ->
		stream.kind == StreamKind.Audio &&
			listOf(stream.displayTitle, stream.extendedDisplayTitle, stream.title).any {
				it?.contains("Atmos", ignoreCase = true) == true
			}
	}

object ItemText {
	/** "42 min", "2 h 14 m", "2 h" (DESIGN §8.3). */
	fun duration(minutes: Int): String {
		val hours = minutes / 60
		val rest = minutes % 60
		if (hours == 0) return "$rest min"
		return if (rest == 0) "$hours h" else "$hours h $rest m"
	}

	/** A runtime in milliseconds, rounded to the minute; null when unknown. */
	fun runtime(milliseconds: Int): String? {
		if (milliseconds < 30_000) return null
		return duration(max(1, (milliseconds / 60_000.0).roundToInt()))
	}

	/** "42 min left". */
	fun left(minutes: Int): String = "${duration(minutes)} left"

	/** "1 hour 12 minutes left", for TalkBack. */
	fun spokenLeft(minutes: Int): String {
		val hours = minutes / 60
		val rest = minutes % 60
		val parts = buildList {
			if (hours > 0) add(if (hours == 1) "1 hour" else "$hours hours")
			if (rest > 0) add(if (rest == 1) "1 minute" else "$rest minutes")
		}
		return parts.joinToString(" ") + " left"
	}

	/** An air date as the device writes dates, from Plex's "2024-03-07". */
	fun airDate(value: String?): String? {
		if (value == null) return null
		return try {
			DATE.format(LocalDate.parse(value))
		} catch (_: DateTimeParseException) {
			null
		}
	}

	/** A score out of 10 with one decimal: "7.8". */
	fun score(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

	/** A file size as the iPhone writes it: powers of 1000, about three significant digits ("850 MB", "4.79 GB", "12 GB"). */
	fun fileSize(bytes: Long): String {
		var value = bytes.toDouble()
		var unit = 0
		while (value >= 1000 && unit < FILE_UNITS.lastIndex) {
			value /= 1000
			unit++
		}
		val digits = if (unit == 0 || value >= 100) 0 else if (value >= 10) 1 else 2
		val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = digits }
		return "${number.format(value)} ${FILE_UNITS[unit]}"
	}

	private val FILE_UNITS = listOf("bytes", "KB", "MB", "GB", "TB")
	private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}

package com.nphil.marquee.playback

import android.media.MediaCodecList
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.StreamKind
import java.util.Locale

/** Whether this tablet has a decoder for a MIME type. */
fun interface Decoders {
	fun canDecode(mime: String): Boolean
}

/** The decoders in this tablet, read once from the codec list. Software decoders count: a trailer is small. */
class DeviceDecoders private constructor(private val types: Set<String>) : Decoders {
	override fun canDecode(mime: String): Boolean = mime.lowercase(Locale.ROOT) in types

	companion object {
		/** The codec list takes tens of milliseconds to build and never changes while the app runs; ask off the main thread. */
		val installed: DeviceDecoders by lazy { read() }

		internal fun read(): DeviceDecoders = DeviceDecoders(
			MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
				.filter { !it.isEncoder }
				.flatMapTo(HashSet()) { info -> info.supportedTypes.map { it.lowercase(Locale.ROOT) } },
		)
	}
}

/**
 * The file a trailer or an extra plays untouched. Media3 reads MP4, MKV, WebM and transport streams itself, so
 * unlike the iPhone nothing is remuxed; a file whose video or audio the tablet cannot decode is passed over rather
 * than asking the server to convert it, which a still image's worth of preview is not worth.
 */
object DirectPlay {
	private val containers = setOf("mp4", "m4v", "mov", "mkv", "matroska", "webm", "mpegts", "ts")

	private val videoMime = mapOf(
		"h264" to "video/avc",
		"avc" to "video/avc",
		"hevc" to "video/hevc",
		"h265" to "video/hevc",
		"vp9" to "video/x-vnd.on2.vp9",
		"vp8" to "video/x-vnd.on2.vp8",
		"av1" to "video/av01",
		"mpeg4" to "video/mp4v-es",
		"mpeg2video" to "video/mpeg2",
		"h263" to "video/3gpp",
	)

	private val audioMime = mapOf(
		"aac" to "audio/mp4a-latm",
		"ac3" to "audio/ac3",
		"eac3" to "audio/eac3",
		"mp3" to "audio/mpeg",
		"mp2" to "audio/mpeg-L2",
		"opus" to "audio/opus",
		"vorbis" to "audio/vorbis",
		"flac" to "audio/flac",
		"dts" to "audio/vnd.dts",
		"dca" to "audio/vnd.dts",
		"truehd" to "audio/true-hd",
	)

	/** Every Android device decodes these (the Compatibility Definition requires them), whatever its codec list says. */
	private val required = setOf("video/avc", "audio/mp4a-latm")

	/** The first part, in version order, that plays as it is; null when none does. */
	fun part(media: List<PlexMedia>, decoders: Decoders): PlexPart? {
		for (version in media) {
			for (part in version.parts) {
				if (part.key.isNotEmpty() && plays(version, part, decoders)) return part
			}
		}
		return null
	}

	private fun plays(version: PlexMedia, part: PlexPart, decoders: Decoders): Boolean {
		val container = (part.container ?: version.container)?.lowercase(Locale.ROOT)
		if (container != null && container !in containers) return false
		val video = version.videoCodec ?: part.streams.firstOrNull { it.kind == StreamKind.Video }?.codec
		val audio = version.audioCodec ?: part.streams.firstOrNull { it.kind == StreamKind.Audio }?.codec
		// A codec the server did not report cannot be ruled out; a file that then fails to start is dropped by the player.
		return (video == null || decodes(video, videoMime, decoders)) && (audio == null || decodes(audio, audioMime, decoders))
	}

	private fun decodes(codec: String, table: Map<String, String>, decoders: Decoders): Boolean {
		val name = codec.lowercase(Locale.ROOT)
		// Media3 reads raw PCM without a codec.
		if (name.startsWith("pcm")) return true
		val mime = table[name] ?: return false
		return mime in required || decoders.canDecode(mime)
	}
}

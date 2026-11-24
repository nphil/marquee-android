package com.nphil.marquee.live

import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.nphil.marquee.playback.Decoders
import com.nphil.marquee.playback.DeviceDecoders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The picture and sound a tuner sends for a channel, as MIME types, so this tablet's decoders can be asked before the
 * tuner is touched. An HDHomeRun names both in its lineup. A LumaTV names neither: it passes an ATSC 1.0 broadcast
 * through, whose picture is MPEG-2 and sound AC-3, and its slates are MPEG-2 as well. Any other tuner that names
 * nothing is not second-guessed.
 */
internal class TunerCodecs(val video: String?, val audio: String?, private val videoAlternatives: List<String> = emptyList()) {
	/** What the channel needs and [decoders] cannot decode; a picture with [videoAlternatives] is missing only when none decodes. */
	fun missing(decoders: Decoders): List<String> = listOfNotNull(
		video?.takeUnless { decoders.canDecode(it) || videoAlternatives.any(decoders::canDecode) },
		audio?.takeUnless(decoders::canDecode),
	)
}

/** [transcode]: the tune may ask a LumaTV for a conversion (H.264 or HEVC), which it may answer with the broadcast's MPEG-2 instead, so any of them plays. */
internal fun tunerCodecs(entry: JsonObject?, isLuma: Boolean, transcode: Boolean = false): TunerCodecs {
	val video = entry?.codec("VideoCodec")
	val audio = entry?.codec("AudioCodec")
	return TunerCodecs(
		video = if (video != null) VIDEO[video] else MimeTypes.VIDEO_MPEG2.takeIf { isLuma },
		audio = if (audio != null) AUDIO[audio] else MimeTypes.AUDIO_AC3.takeIf { isLuma },
		videoAlternatives = if (isLuma && transcode && video == null) listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265) else emptyList(),
	)
}

/**
 * The MIME types of a stream's picture or sound that no renderer can play at all. A track a decoder merely lists as
 * beyond its limits is still tried, as Plex's direct play does; a decoder that then fails to start ends the stream.
 */
internal fun Tracks.unplayable(): List<String> = intArrayOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
	.filter { containsType(it) && !isTypeSupported(it, true) }
	.flatMap { type -> formats(type).map { (format, _) -> format.sampleMimeType ?: "unknown" } }
	.distinct()

/** One log value for the tracks of [type]: each format, then the verdict on it, e.g. `video/mpeg2 1920x1080 29.97fps handled`. */
internal fun Tracks.describe(type: Int): String = formats(type).joinToString(", ") { (format, support) ->
	listOfNotNull(
		format.sampleMimeType,
		format.width.takeIf { it > 0 }?.let { "${it}x${format.height}" },
		format.frameRate.takeIf { it > 0 }?.let { "${it}fps" },
		format.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
		format.sampleRate.takeIf { it > 0 }?.let { "${it}Hz" },
		when (support) {
			C.FORMAT_HANDLED -> "handled"
			C.FORMAT_EXCEEDS_CAPABILITIES -> "exceeds_capabilities"
			C.FORMAT_UNSUPPORTED_DRM -> "unsupported_drm"
			C.FORMAT_UNSUPPORTED_SUBTYPE -> "unsupported_subtype"
			else -> "unsupported_type"
		},
	).joinToString(" ")
}.ifEmpty { "none" }

/** The decoders for [mime], by name, for the log: the codec list's, else "ffmpeg" when the app's own build decodes it. */
internal fun decoderNames(mime: String): String = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
	.filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
	.joinToString("+") { it.name }.ifEmpty { if (DeviceDecoders.installed.canDecode(mime)) "ffmpeg" else "none" }

private fun Tracks.formats(type: Int) = groups.filter { it.type == type }
	.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) to group.getTrackSupport(it) } }

private val VIDEO = mapOf("MPEG2" to MimeTypes.VIDEO_MPEG2, "H264" to MimeTypes.VIDEO_H264, "HEVC" to MimeTypes.VIDEO_H265)
private val AUDIO = mapOf(
	"AC3" to MimeTypes.AUDIO_AC3, "EAC3" to MimeTypes.AUDIO_E_AC3, "AAC" to MimeTypes.AUDIO_AAC, "MPEG" to MimeTypes.AUDIO_MPEG_L2,
)

private fun JsonObject.codec(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.uppercase()

/**
 * Whether a decoder here plays [codec] (`h264` or `hevc`, as a tuner's profile names it) at [height] lines, the width
 * being 16:9; an unknown height asks only for the codec. The size is the decoder's own limit, not a cap of the app's.
 */
internal fun decodesVideo(codec: String, height: Int?): Boolean {
	val mime = when (codec) {
		"h264" -> MimeTypes.VIDEO_H264
		"hevc" -> MimeTypes.VIDEO_H265
		else -> return false
	}
	if (height == null) return DeviceDecoders.installed.canDecode(mime)
	return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
		!info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
			info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(height * 16 / 9, height) == true
	}
}

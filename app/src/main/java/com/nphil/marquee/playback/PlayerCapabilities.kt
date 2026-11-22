package com.nphil.marquee.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.nphil.marquee.core.Capabilities

/**
 * What this tablet's decoders and screen take, in the terms Core's route rules ask. Audio the tablet has no decoder
 * for (DTS and TrueHD on most) is left out, so Core has the server convert only the audio and copy the video.
 */
object PlayerCapabilities {
	private val containers = listOf("mp4", "m4v", "mov", "mkv", "matroska", "webm")

	/** Plex's audio codec names, and the MIME type whose decoder plays each. */
	private val audioMime = linkedMapOf(
		"aac" to "audio/mp4a-latm",
		"ac3" to "audio/ac3",
		"eac3" to "audio/eac3",
		"mp3" to "audio/mpeg",
		"mp2" to "audio/mpeg-L2",
		"opus" to "audio/opus",
		"vorbis" to "audio/vorbis",
		"flac" to "audio/flac",
		"alac" to "audio/alac",
		"dca" to "audio/vnd.dts",
		"dts" to "audio/vnd.dts",
		"truehd" to "audio/true-hd",
		"mlp" to "audio/true-hd",
	)

	/** Media3 reads raw PCM without a codec. */
	private val pcm = listOf("pcm_u8", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_f32le")

	/** Every Android device decodes AAC, whatever its codec list says. */
	private const val AAC = "audio/mp4a-latm"

	private val mainTenProfiles = setOf(
		CodecProfileLevel.HEVCProfileMain10,
		CodecProfileLevel.HEVCProfileMain10HDR10,
		CodecProfileLevel.HEVCProfileMain10HDR10Plus,
	)

	/** The Plex audio codec names [decoders] play, then PCM. */
	internal fun audioCodecs(decoders: Decoders): List<String> =
		audioMime.filter { (_, mime) -> mime == AAC || decoders.canDecode(mime) }.keys.toList() + pcm

	/** The tablet's capabilities; the codec list takes tens of milliseconds to read, so ask off the main thread. */
	fun read(context: Context): Capabilities {
		val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
		fun profiles(mime: String): List<Int> = decoders.flatMap { info ->
			val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@flatMap emptyList()
			info.getCapabilitiesForType(type).profileLevels.map { it.profile }
		}
		val hevc = decoders.any { info -> info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } }
		val hevcMain10 = profiles("video/hevc").any { it in mainTenProfiles }
		val av1 = decoders.any { info -> info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } }
		// A Dolby Vision decoder reports its profiles as one bit each: profile n is bit n. Profile 7 plays its HDR10 base
		// layer on any HEVC Main 10 decoder, which skips the enhancement layer.
		val dolbyVision = profiles("video/dolby-vision").map { Integer.numberOfTrailingZeros(it) }.toMutableSet()
		if (hevcMain10) dolbyVision += 7
		return Capabilities(
			hevc = hevc,
			hevcMain10 = hevcMain10,
			av1 = av1,
			hdrDisplay = hdrDisplay(context),
			dolbyVisionProfiles = dolbyVision.sorted(),
			nativeAudioCodecs = audioCodecs(DeviceDecoders.installed),
			directContainers = containers,
		)
	}

	@Suppress("DEPRECATION")
	private fun hdrDisplay(context: Context): Boolean {
		val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
		return if (Build.VERSION.SDK_INT >= 34) {
			display.mode.supportedHdrTypes.isNotEmpty()
		} else {
			display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
		}
	}
}

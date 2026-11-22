package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nphil.marquee.core.CoreJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.math.roundToInt

/** The ready-made subtitle looks, named as the iPhone names them. */
enum class SubtitlePreset(val title: String) {
	Blend("Blend with Video"),
	AppleTV("Apple TV"),
	Classic("Classic"),
	Netflix("Netflix"),
	Outline("Outline"),
	LargePrint("Large Print"),
	HighContrast("High Contrast"),
}

enum class SubtitleFont { Standard, Mono, Legible }

enum class SubtitleEdge { None, Outline, DropShadow, SoftShadow }

enum class SubtitleReveal(val title: String) {
	Off("Off"),
	Word("Word by Word"),
	Line("Line by Line"),
}

/** Text colours; the channels are the iPhone's, 0 to 1. */
enum class SubtitleColour(val title: String, val red: Float, val green: Float, val blue: Float) {
	White("White", 1f, 1f, 1f),
	SoftWhite("Soft White", .88f, .88f, .88f),
	Yellow("Yellow", 1f, .9f, .3f),
	Cyan("Cyan", .45f, 1f, 1f),
	Green("Green", .5f, .95f, .5f),
	Pink("Pink", 1f, .62f, .84f),
}

/**
 * How text subtitles look over the picture, kept per profile. [Blend][SubtitlePreset.Blend] holds the text just under
 * full brightness, so a line never reads as the brightest thing in a dark scene; no preset goes above the owner's own
 * SDR white.
 */
@Immutable
@Serializable
data class SubtitleLook(
	val preset: SubtitlePreset = SubtitlePreset.Blend,
	val font: SubtitleFont = SubtitleFont.Standard,
	val bold: Boolean = true,
	/** The text size against the standard line, in percent: one of [SIZES]. */
	val size: Int = 100,
	val colour: SubtitleColour = SubtitleColour.White,
	/** How solid the text is, in percent. */
	val opacity: Int = 90,
	val edge: SubtitleEdge = SubtitleEdge.SoftShadow,
	/** A box behind each line of text, and how solid it is in percent. */
	val box: Boolean = false,
	val boxOpacity: Int = 75,
	/** The colour is lifted off pure white a little: see [textArgb]. */
	val softens: Boolean = true,
	val reveal: SubtitleReveal = SubtitleReveal.Off,
) {
	/** Reveal belongs to the text, independently of which visual preset is in use. */
	val isPreset: Boolean get() = this == of(preset, reveal)

	/** The text colour as drawn: ARGB, the picked colour softened when [softens], at [opacity]. */
	val textArgb: Int
		get() {
			fun channel(value: Float) = ((if (softens) value * SOFTEN_SCALE + SOFTEN_LIFT else value) * 255f).roundToInt().coerceIn(0, 255)
			val alpha = (opacity.coerceIn(0, 100) * 255 / 100)
			return (alpha shl 24) or (channel(colour.red) shl 16) or (channel(colour.green) shl 8) or channel(colour.blue)
		}

	/** The box's colour as drawn: ARGB black at [boxOpacity], or clear when there is no box. */
	val boxArgb: Int get() = if (box) (boxOpacity.coerceIn(0, 100) * 255 / 100) shl 24 else 0

	companion object {
		val SIZES = listOf(75, 85, 100, 115, 130, 150, 175, 200)

		// Soften maps 1.0 to 0.94, so the brightest subtitle sits a little under the panel's white.
		private const val SOFTEN_SCALE = 0.88f
		private const val SOFTEN_LIFT = 0.06f

		/** Every setting the preset defines. */
		fun of(preset: SubtitlePreset, reveal: SubtitleReveal = SubtitleReveal.Off): SubtitleLook = when (preset) {
			SubtitlePreset.Blend -> SubtitleLook(reveal = reveal)
			SubtitlePreset.AppleTV -> SubtitleLook(preset, opacity = 100, softens = false, reveal = reveal)
			SubtitlePreset.Classic -> SubtitleLook(
				preset, SubtitleFont.Mono, bold = false, opacity = 100, edge = SubtitleEdge.None, box = true, boxOpacity = 100,
				softens = false, reveal = reveal,
			)
			SubtitlePreset.Netflix -> SubtitleLook(preset, opacity = 100, edge = SubtitleEdge.DropShadow, softens = false, reveal = reveal)
			SubtitlePreset.Outline -> SubtitleLook(preset, opacity = 100, edge = SubtitleEdge.Outline, softens = false, reveal = reveal)
			SubtitlePreset.LargePrint -> SubtitleLook(
				preset, SubtitleFont.Legible, size = 150, opacity = 100, edge = SubtitleEdge.Outline, softens = false, reveal = reveal,
			)
			SubtitlePreset.HighContrast -> SubtitleLook(
				preset, SubtitleFont.Legible, colour = SubtitleColour.Yellow, opacity = 100, edge = SubtitleEdge.Outline,
				box = true, boxOpacity = 90, softens = false, reveal = reveal,
			)
		}
	}
}

private fun lookKey(profile: String) = stringPreferencesKey("subtitles.$profile.look")

internal fun Preferences.subtitleLook(profile: String?): SubtitleLook {
	val saved = profile?.let { this[lookKey(it)] } ?: return SubtitleLook()
	return try {
		CoreJson.decodeFromString(SubtitleLook.serializer(), saved).let { it.copy(size = it.size.takeIf { size -> size in SubtitleLook.SIZES } ?: 100) }
	} catch (_: SerializationException) {
		SubtitleLook()
	}
}

internal fun MutablePreferences.setSubtitleLook(profile: String, look: SubtitleLook) {
	this[lookKey(profile)] = CoreJson.encodeToString(SubtitleLook.serializer(), look)
}

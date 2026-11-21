package com.nphil.marquee.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

enum class ThemeMode { Light, Dark }

/**
 * One theme of the Lucent catalogue (core/Resources/themes.json, DESIGN §5.1): the 20 colour roles every
 * surface draws with, plus [accentText], the accent where it is text.
 */
@Immutable
class LucentTheme(
	val id: String,
	val name: String,
	val mode: ThemeMode,
	val canvas: Color,
	val sheet: Color,
	val scrim: Color,
	val glass: Color,
	val glassRaised: Color,
	val edge: Color,
	val edgeRaised: Color,
	val ink: Color,
	val ink2: Color,
	val ink3: Color,
	val focus: Color,
	val focusInk: Color,
	val trackOff: Color,
	val accent: Color,
	val accentInk: Color,
	val positive: Color,
	val warning: Color,
	val danger: Color,
	val info: Color,
	val live: Color,
) {
	/**
	 * The accent itself when it reads at 4.5:1 on canvas, otherwise mixed toward ink in 5% steps until it
	 * does. Only Rosé Pine Dawn needs the mix (#907AA9 becomes #78658D).
	 */
	val accentText: Color = (0..20).asSequence()
		.map { step -> mix(accent, ink, step * 0.05f) }
		.firstOrNull { contrast(it.compositeOver(canvas), canvas) >= 4.5f }
		?: ink

	val isDark: Boolean get() = mode == ThemeMode.Dark

	companion object {
		/** The catalogue's own default themes, used until something else is chosen. */
		const val DEFAULT_DARK = "rose-pine"
		const val DEFAULT_LIGHT = "rose-pine-dawn"

		/** The id of the theme drawn from the tablet's wallpaper (Android 12 and later). */
		const val WALLPAPER = "wallpaper"
	}
}

/** The bundled themes, in catalogue order. */
class ThemeCatalog(val themes: List<LucentTheme>) {
	private val byId = themes.associateBy { it.id }

	fun themes(mode: ThemeMode): List<LucentTheme> = themes.filter { it.mode == mode }

	operator fun get(id: String): LucentTheme? = byId[id]

	/** The default for [mode], which the catalogue always has. */
	fun default(mode: ThemeMode): LucentTheme =
		byId.getValue(if (mode == ThemeMode.Dark) LucentTheme.DEFAULT_DARK else LucentTheme.DEFAULT_LIGHT)

	companion object {
		fun parse(json: String): ThemeCatalog {
			val file = Json { ignoreUnknownKeys = true }.decodeFromString<ThemeFile>(json)
			return ThemeCatalog(file.themes.map { it.theme() })
		}
	}
}

@Serializable
private class ThemeFile(val themes: List<ThemeEntry>)

@Serializable
private class ThemeEntry(val id: String, val name: String, val mode: String, val colors: Map<String, String>) {
	fun theme(): LucentTheme {
		fun role(name: String) = hexColor(colors.getValue(name))
		return LucentTheme(
			id = id,
			name = name,
			mode = if (mode == "dark") ThemeMode.Dark else ThemeMode.Light,
			canvas = role("canvas"),
			sheet = role("sheet"),
			scrim = role("scrim"),
			glass = role("glass"),
			glassRaised = role("glassRaised"),
			edge = role("edge"),
			edgeRaised = role("edgeRaised"),
			ink = role("ink"),
			ink2 = role("ink2"),
			ink3 = role("ink3"),
			focus = role("focus"),
			focusInk = role("focusInk"),
			trackOff = role("trackOff"),
			accent = role("accent"),
			accentInk = role("accentInk"),
			positive = role("positive"),
			warning = role("warning"),
			danger = role("danger"),
			info = role("info"),
			live = role("live"),
		)
	}
}

/** `#RRGGBB` or `#RRGGBBAA`. */
internal fun hexColor(hex: String): Color {
	require(hex.startsWith('#') && (hex.length == 7 || hex.length == 9)) { "$hex is not #RRGGBB or #RRGGBBAA" }
	val value = hex.substring(1).toLong(16)
	return if (hex.length == 7) {
		Color(0xFF000000 or value)
	} else {
		Color(((value and 0xFF) shl 24) or (value ushr 8))
	}
}

/** [fraction] of the way from [from] to [to], mixed per sRGB component as check_themes.py does. */
internal fun mix(from: Color, to: Color, fraction: Float) = Color(
	red = from.red + (to.red - from.red) * fraction,
	green = from.green + (to.green - from.green) * fraction,
	blue = from.blue + (to.blue - from.blue) * fraction,
	alpha = from.alpha + (to.alpha - from.alpha) * fraction,
)

/** WCAG contrast ratio of two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
	val la = a.luminance()
	val lb = b.luminance()
	return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

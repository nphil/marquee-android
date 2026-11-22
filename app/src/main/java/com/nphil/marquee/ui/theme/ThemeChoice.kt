package com.nphil.marquee.ui.theme

import androidx.compose.runtime.Immutable

enum class HeaderStyle(val description: String) {
	Soft("Fades the page edge"),
	Hard("Draws a line under the header"),
	Blur("Frosts the page edge on Android 12 and later"),
}

/**
 * Which themes are chosen (iPhone ThemeStore, DESIGN §8.7): one for light and one for dark, following the
 * system; or, with [matchSystem] off, the theme of [fixedMode] in both appearances.
 */
@Immutable
data class ThemeChoice(
	val matchSystem: Boolean = true,
	val lightId: String = LucentTheme.DEFAULT_LIGHT,
	val darkId: String = LucentTheme.DEFAULT_DARK,
	val fixedMode: ThemeMode = ThemeMode.Dark,
	val headerStyle: HeaderStyle = HeaderStyle.Soft,
	val showsScrollBars: Boolean = true,
) {
	/** The appearance on screen. */
	fun mode(systemDark: Boolean): ThemeMode = when {
		!matchSystem -> fixedMode
		systemDark -> ThemeMode.Dark
		else -> ThemeMode.Light
	}

	fun id(mode: ThemeMode): String = if (mode == ThemeMode.Dark) darkId else lightId

	/** Fills [mode]'s slot with [id]; with matching off, [mode] becomes the appearance. */
	fun select(id: String, mode: ThemeMode): ThemeChoice {
		val filled = if (mode == ThemeMode.Dark) copy(darkId = id) else copy(lightId = id)
		return if (matchSystem) filled else filled.copy(fixedMode = mode)
	}

	/** Turning matching off keeps the appearance on screen. */
	fun matching(on: Boolean, systemDark: Boolean): ThemeChoice =
		if (on) copy(matchSystem = true) else copy(matchSystem = false, fixedMode = mode(systemDark))

	/** A tile is chosen when its slot holds it and that slot is in use. */
	fun isSelected(id: String, mode: ThemeMode): Boolean = id(mode) == id && (matchSystem || mode == fixedMode)
}

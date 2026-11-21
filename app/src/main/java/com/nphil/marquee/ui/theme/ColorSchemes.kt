package com.nphil.marquee.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** A Lucent theme and the Material scheme its components draw with. */
class ResolvedTheme(val lucent: LucentTheme, val scheme: ColorScheme)

/**
 * The Material 3 roles of a Lucent theme. Material composites nothing, so translucent roles are laid over
 * canvas first; the containers step up from canvas through sheet to glassRaised, as Lucent's surfaces do.
 */
fun LucentTheme.colorScheme(): ColorScheme {
	val sheetSolid = sheet.compositeOver(canvas)
	val raised = glassRaised.compositeOver(canvas)
	val track = trackOff.compositeOver(canvas)
	val ink2Solid = ink2.compositeOver(canvas)
	val accentContainer = tint(accent)
	val infoContainer = tint(info)
	return ColorScheme(
		primary = accent,
		onPrimary = accentInk,
		primaryContainer = accentContainer,
		onPrimaryContainer = ink,
		inversePrimary = canvas,
		secondary = accentText,
		onSecondary = canvas,
		secondaryContainer = accentContainer,
		onSecondaryContainer = ink,
		tertiary = info,
		onTertiary = canvas,
		tertiaryContainer = infoContainer,
		onTertiaryContainer = ink,
		background = canvas,
		onBackground = ink,
		surface = canvas,
		onSurface = ink,
		surfaceVariant = track,
		onSurfaceVariant = ink2Solid,
		surfaceTint = ink,
		inverseSurface = ink,
		inverseOnSurface = canvas,
		error = danger,
		onError = canvas,
		errorContainer = tint(danger),
		onErrorContainer = ink,
		outline = ink2Solid,
		outlineVariant = track,
		scrim = scrim.copy(alpha = 1f),
		surfaceBright = raised,
		surfaceDim = canvas,
		surfaceContainer = sheetSolid,
		surfaceContainerHigh = mix(sheetSolid, raised, 0.5f),
		surfaceContainerHighest = raised,
		surfaceContainerLow = mix(canvas, sheetSolid, 0.5f),
		surfaceContainerLowest = canvas,
		primaryFixed = accent,
		primaryFixedDim = accent,
		onPrimaryFixed = accentInk,
		onPrimaryFixedVariant = accentInk,
		secondaryFixed = accentContainer,
		secondaryFixedDim = accentContainer,
		onSecondaryFixed = ink,
		onSecondaryFixedVariant = ink2Solid,
		tertiaryFixed = infoContainer,
		tertiaryFixedDim = infoContainer,
		onTertiaryFixed = ink,
		onTertiaryFixedVariant = ink2Solid,
	)
}

/**
 * The Wallpaper theme: Lucent roles read back from Android's wallpaper scheme. The scheme has no tones
 * for good, slow or live, so those come from the catalogue default of the same mode.
 */
fun wallpaperTheme(scheme: ColorScheme, mode: ThemeMode, base: LucentTheme) = LucentTheme(
	id = LucentTheme.WALLPAPER,
	name = "Wallpaper",
	mode = mode,
	canvas = scheme.surface,
	sheet = scheme.surfaceContainer,
	scrim = base.scrim,
	glass = scheme.surfaceContainerLow,
	glassRaised = scheme.surfaceContainerHighest,
	edge = scheme.outlineVariant,
	edgeRaised = scheme.outline,
	ink = scheme.onSurface,
	ink2 = scheme.onSurfaceVariant,
	ink3 = scheme.outline,
	focus = scheme.onSurface,
	focusInk = scheme.surface,
	trackOff = scheme.surfaceContainerHighest,
	accent = scheme.primary,
	accentInk = scheme.onPrimary,
	positive = base.positive,
	warning = base.warning,
	danger = scheme.error,
	info = base.info,
	live = base.live,
)

/** [colour] at 22% over canvas: a container in that colour's tone that ink still reads on. */
private fun LucentTheme.tint(colour: Color) = colour.copy(alpha = 0.22f).compositeOver(canvas)

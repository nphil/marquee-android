package com.nphil.marquee.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nphil.marquee.ui.theme.contrast
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.theme.mix

/**
 * The colours of the player's controls. The picture is dark whatever the theme is, so the controls are light marks on
 * dark shades on every theme: a light theme's scrim and ink are a pale wash and dark text, which would film the
 * video over. This is the one place the app uses fixed colours instead of the theme's tokens. The accent still comes
 * from the theme, through [playerAccent].
 */
internal object PlayerInk {
	val ink = Color(0xFFF4F4F6)
	val ink2 = ink.copy(alpha = 0.76f)
	val ink3 = ink.copy(alpha = 0.44f)

	/** The dark disc behind a button, and the same pressed. */
	val glass = Color.Black.copy(alpha = 0.40f)
	val glassPressed = Color.Black.copy(alpha = 0.58f)

	/** A label's capsule: a little darker than a button, as it holds text over any picture. */
	val chip = Color.Black.copy(alpha = 0.62f)
	val edge = Color.White.copy(alpha = 0.16f)

	/** The play button's fill, and the glyph on it. */
	val solid = ink
	val onSolid = Color(0xFF111114)

	/** Text and marks on the lifted accent. The accent reads on black at 7:1, so near-black reads on it. */
	val onAccent = Color(0xFF111114)

	val trackOff = Color.White.copy(alpha = 0.28f)
	val trackMark = Color.White.copy(alpha = 0.52f)

	/** The Up Next card: opaque, so its still and text never sit on the moving picture. */
	val card = Color(0xFF1B1B1F)
	val cardEdge = Color.White.copy(alpha = 0.12f)

	val topScrim: Brush = Brush.verticalGradient(
		0f to Color.Black.copy(alpha = 0.70f),
		0.55f to Color.Black.copy(alpha = 0.30f),
		1f to Color.Transparent,
	)
	val bottomScrim: Brush = Brush.verticalGradient(
		0f to Color.Transparent,
		0.45f to Color.Black.copy(alpha = 0.34f),
		1f to Color.Black.copy(alpha = 0.74f),
	)
}

/** The least contrast against black that [playerAccent] keeps. */
private const val ACCENT_CONTRAST = 7f

/**
 * [accent] on the player's dark shades: itself when it already reads at 7:1 on black, otherwise mixed toward white in
 * small steps until it does. Dark themes' accents pass as they are; a light theme's deep blue or ink-black accent
 * would vanish on the picture.
 */
internal fun playerAccent(accent: Color): Color {
	for (step in 0..20) {
		val lifted = mix(accent, Color.White, step * 0.05f)
		if (contrast(lifted, Color.Black) >= ACCENT_CONTRAST) return lifted
	}
	return Color.White
}

/** The theme's accent as the player draws it: the played part of the bar, the buffered part, and what is chosen. */
@Composable
internal fun rememberPlayerAccent(): Color {
	val accent = lucent.accent
	return remember(accent) { playerAccent(accent) }
}

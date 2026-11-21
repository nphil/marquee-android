package com.nphil.marquee.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale from DESIGN §4.3: Manrope for display and titles, the system font for the rest. Material's
 * slots carry Lucent's roles: displaySmall is display, headlineSmall is title, titleMedium is headline,
 * bodyLarge is body, bodyMedium and labelLarge are label, bodySmall is caption.
 */
fun lucentTypography(assets: AssetManager): Typography {
	val manrope = FontFamily(
		manrope(assets, FontWeight.Medium),
		manrope(assets, FontWeight.SemiBold),
		manrope(assets, FontWeight.Bold),
	)
	val sans = FontFamily.Default
	fun style(family: FontFamily, weight: FontWeight, size: Int, lineHeight: Float, tracking: Float = 0f) = TextStyle(
		fontFamily = family,
		fontWeight = weight,
		fontSize = size.sp,
		lineHeight = (size * lineHeight).sp,
		letterSpacing = tracking.em,
	)
	return Typography(
		displayLarge = style(manrope, FontWeight.Medium, 57, 1.1f, -0.02f),
		displayMedium = style(manrope, FontWeight.Medium, 45, 1.1f, -0.02f),
		displaySmall = style(manrope, FontWeight.Medium, 34, 1.0f, -0.02f),
		headlineLarge = style(manrope, FontWeight.SemiBold, 32, 1.25f, -0.01f),
		headlineMedium = style(manrope, FontWeight.SemiBold, 28, 1.25f, -0.01f),
		headlineSmall = style(manrope, FontWeight.SemiBold, 24, 1.25f, -0.01f),
		titleLarge = style(manrope, FontWeight.SemiBold, 22, 1.25f, -0.01f),
		titleMedium = style(sans, FontWeight.SemiBold, 17, 1.3f),
		titleSmall = style(sans, FontWeight.Medium, 15, 1.3f),
		bodyLarge = style(sans, FontWeight.Normal, 17, 1.45f),
		bodyMedium = style(sans, FontWeight.Medium, 15, 1.3f),
		bodySmall = style(sans, FontWeight.Normal, 12, 1.3f),
		labelLarge = style(sans, FontWeight.Medium, 15, 1.3f),
		labelMedium = style(sans, FontWeight.Medium, 13, 1.3f),
		labelSmall = style(sans, FontWeight.Medium, 12, 1.3f),
	)
}

/** One cut of the bundled variable Manrope, chosen by its weight axis. */
private fun manrope(assets: AssetManager, weight: FontWeight) = Font(
	path = "fonts/Manrope.ttf",
	assetManager = assets,
	weight = weight,
	variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

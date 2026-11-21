package com.nphil.marquee.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** The bundled catalogue plus, from Android 12, the Wallpaper theme in each mode. */
class Themes(private val context: Context, val catalog: ThemeCatalog) {
	val hasWallpaper: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

	/** The tiles of [mode]'s grid: Wallpaper first where the tablet has it. */
	fun choices(mode: ThemeMode): List<LucentTheme> {
		val bundled = catalog.themes(mode)
		return if (hasWallpaper) listOf(resolve(LucentTheme.WALLPAPER, mode).lucent) + bundled else bundled
	}

	/** The theme with [id] in [mode]; an id this tablet cannot show falls back to the mode's default. */
	fun resolve(id: String, mode: ThemeMode): ResolvedTheme {
		if (id == LucentTheme.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val scheme = if (mode == ThemeMode.Dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
			return ResolvedTheme(wallpaperTheme(scheme, mode, catalog.default(mode)), scheme)
		}
		val theme = catalog[id]?.takeIf { it.mode == mode } ?: catalog.default(mode)
		return ResolvedTheme(theme, theme.colorScheme())
	}
}

private val LocalLucent = staticCompositionLocalOf<LucentTheme> { error("MarqueeTheme is not applied") }

/** The active theme's Lucent roles, for what Material has no role for. */
val lucent: LucentTheme
	@Composable @ReadOnlyComposable get() = LocalLucent.current

@Composable
fun MarqueeTheme(theme: ResolvedTheme, typography: Typography, content: @Composable () -> Unit) {
	CompositionLocalProvider(LocalLucent provides theme.lucent) {
		MaterialExpressiveTheme(
			colorScheme = theme.scheme,
			motionScheme = MotionScheme.expressive(),
			shapes = LucentShapes,
			typography = typography,
			content = content,
		)
	}
}

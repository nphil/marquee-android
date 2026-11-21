package com.nphil.marquee.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ThemeMappingTest {
	private val catalog = ThemeCatalog.parse(File(System.getProperty("marquee.themes")!!).readText())

	@Test
	fun accentTextDarkensOnlyAccentsThatReadPoorly() {
		assertEquals(0xFF78658D.toInt(), catalog["rose-pine-dawn"]!!.accentText.toArgb())
		val dark = catalog["rose-pine"]!!
		assertEquals(dark.accent.toArgb(), dark.accentText.toArgb())
	}

	@Test
	fun everyThemeMapsOntoMaterialRoles() {
		assertEquals(20, catalog.themes(ThemeMode.Dark).size)
		assertEquals(20, catalog.themes(ThemeMode.Light).size)
		for (theme in catalog.themes) {
			val scheme = theme.colorScheme()
			assertEquals(theme.id, theme.canvas, scheme.background)
			assertEquals(theme.id, theme.accentText, scheme.secondary)
		}
	}
}

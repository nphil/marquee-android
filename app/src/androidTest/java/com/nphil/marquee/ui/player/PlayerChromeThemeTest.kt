package com.nphil.marquee.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.deviceWindow
import com.nphil.marquee.testing.saveWindow
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucentTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TITLE = "The Long Dark Road"

/** The stand-in for the picture. */
private val VIDEO = Color(0xFF505050)

/** A shade over the picture is no lighter than the picture, give or take rounding. */
private const val EPSILON = 0.01f

/** What counts as a dark shade and as a light mark, by relative luminance. The title's ink measures 0.906. */
private const val DARK = 0.25f
private const val LIGHT = 0.88f

/** How far the shade may differ between two themes: the theme does not tint it. */
private const val THEME_TOLERANCE = 0.03f

/**
 * The player's controls over a mid-grey stand-in for the picture, on a light and a dark theme: they draw dark shades
 * and light marks on both, so a light theme never washes the picture. Screenshots of both are saved for review.
 */
@RunWith(AndroidJUnit4::class)
class PlayerChromeThemeTest {
	@get:Rule
	val rule = createComposeRule()

	private val dark = mutableStateOf(false)

	@Test
	fun theControlsAreDarkOverThePictureOnLightAndDarkThemes() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val catalog = ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() })
		val themes = Themes(context, catalog)
		val typography = lucentTypography(context.assets)
		val lightTheme = themes.resolve(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		val darkTheme = themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			MarqueeTheme(if (dark.value) darkTheme else lightTheme, typography) {
				DeviceWindow(Device.Tablet) { Chrome() }
			}
		}

		val light = shoot("player-chrome-light")
		val title = titleRegion(light)
		dark.value = true
		val night = shoot("player-chrome-dark")

		// A strip at the window's left edge, inside the top shade and clear of every control.
		val scale = light.width / Device.Tablet.size.width.value
		val strip = Region(0, px(8f, scale), px(12f, scale), px(150f, scale))
		val bare = VIDEO.luminance()
		val onLight = light.average(strip)
		val onDark = night.average(strip)
		assertTrue("light theme: shade $onLight over picture $bare", onLight <= bare + EPSILON)
		assertTrue("dark theme: shade $onDark over picture $bare", onDark <= bare + EPSILON)
		assertTrue("light theme: shade $onLight is not dark", onLight < DARK)
		assertTrue("dark theme: shade $onDark is not dark", onDark < DARK)
		assertEquals("the shade follows the theme", onLight, onDark, THEME_TOLERANCE)

		assertTrue("light theme: title is not light", light.brightest(title) >= LIGHT)
		assertTrue("dark theme: title is not light", night.brightest(title) >= LIGHT)
	}

	@Test
	fun theControlsLayOutOnAPhone() = laysOut(Device.Phone)

	@Test
	fun theControlsLayOutOnAPortraitTablet() = laysOut(Device.TabletPortrait)

	private fun laysOut(device: Device) {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val catalog = ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() })
		val theme = Themes(context, catalog).resolve(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		val typography = lucentTypography(context.assets)
		rule.setContent {
			MarqueeTheme(theme, typography) {
				DeviceWindow(device) { Chrome() }
			}
		}
		rule.onNodeWithText(TITLE, useUnmergedTree = true).assertIsDisplayed()
		rule.saveWindow("player-chrome-${device.file}")
	}

	private fun shoot(name: String): Bitmap {
		rule.saveWindow(name)
		return rule.deviceWindow().captureToImage().asAndroidBitmap()
	}

	/** Where the title was drawn, in the pixels of [shot]. */
	private fun titleRegion(shot: Bitmap): Region {
		val scale = shot.width / Device.Tablet.size.width.value
		val window = rule.deviceWindow().getBoundsInRoot()
		val bounds = rule.onNodeWithText(TITLE, useUnmergedTree = true).getBoundsInRoot()
		return Region(
			px(bounds.left - window.left, scale),
			px(bounds.top - window.top, scale),
			px(bounds.right - window.left, scale),
			px(bounds.bottom - window.top, scale),
		)
	}
}

private class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun px(dp: Dp, scale: Float): Int = px(dp.value, scale)

private fun px(dp: Float, scale: Float): Int = (dp * scale).toInt()

private inline fun Bitmap.scan(region: Region, each: (Float) -> Unit) {
	val x0 = region.left.coerceIn(0, width - 1)
	val x1 = region.right.coerceIn(x0 + 1, width)
	val y0 = region.top.coerceIn(0, height - 1)
	val y1 = region.bottom.coerceIn(y0 + 1, height)
	for (y in y0 until y1) {
		for (x in x0 until x1) each(Color(getPixel(x, y)).luminance())
	}
}

private fun Bitmap.average(region: Region): Float {
	var sum = 0.0
	var count = 0
	scan(region) {
		sum += it
		count++
	}
	return (sum / count).toFloat()
}

private fun Bitmap.brightest(region: Region): Float {
	var best = 0f
	scan(region) { if (it > best) best = it }
	return best
}

private val NoActions = ChromeActions(
	back = {},
	toggle = {},
	skip = {},
	subtitles = {},
	audio = {},
	quality = {},
	speed = {},
	pip = {},
	more = {},
	lock = {},
	aspect = {},
	chapters = {},
	episodes = {},
	next = {},
	remaining = {},
)

/** Every control the player has, over the stand-in picture. */
@Composable
private fun Chrome() {
	Box(Modifier.fillMaxSize().background(VIDEO)) {
		PlayerChrome(
			state = ChromeState(
				title = TITLE,
				playing = true,
				positionMs = 754_000L,
				durationMs = 3_600_000L,
				bufferedMs = 1_200_000L,
				cuts = listOf(0.25f, 0.6f),
				bands = listOf(0.02f..0.06f),
				speed = 1.5f,
				hasSubtitles = true,
				subtitlesOn = true,
				hasAudioChoices = true,
				canPip = true,
				hasChapters = true,
				hasEpisodes = true,
				hasNext = true,
			),
			actions = NoActions,
			scrub = remember { ScrubState(began = {}, moved = {}, ended = {}) },
			previews = null,
		)
	}
}

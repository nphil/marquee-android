package com.nphil.marquee.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.R
import com.nphil.marquee.playback.EnhanceUpscaler
import com.nphil.marquee.playback.PictureEnhance
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucentTypography
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The More menu's Enhance Picture row opens its page; choosing Automatic brings the Sharpen and Deband sliders. */
@RunWith(AndroidJUnit4::class)
class EnhancePageTest {
	@get:Rule
	val rule = createComposeRule()

	@Test
	fun openingEnhancePictureAndChoosingAutomaticShowsTheSliders() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val catalog = ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() })
		val theme = Themes(context, catalog).resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent { MarqueeTheme(theme, lucentTypography(context.assets)) { Menu() } }

		rule.onNodeWithText("Enhance Picture").assertIsDisplayed()
		rule.onNodeWithText("Off").assertIsDisplayed()
		rule.onNodeWithText("Enhance Picture").performClick()
		rule.onNodeWithText("Automatic").assertIsDisplayed()
		rule.onNodeWithContentDescription("Sharpen").assertDoesNotExist()

		rule.onNodeWithText("Automatic").performClick()
		rule.onNodeWithContentDescription("Sharpen").assertIsDisplayed()
		rule.onNodeWithContentDescription("Deband").assertIsDisplayed()
	}

	@Composable
	private fun Menu() {
		var setting by remember { mutableStateOf(PictureEnhance()) }
		var open by remember { mutableStateOf(false) }
		Column {
			if (!open) {
				NavRow(0, 1, "Enhance Picture", enhanceRowValue(setting, null), R.drawable.ic_contrast) { open = true }
			} else {
				EnhancePage(
					setting, unavailable = null, fade = 1f, onDragging = {},
					onUpscaler = { upscaler: EnhanceUpscaler -> setting = setting.copy(upscaler = upscaler) },
					onStrength = { which, value, _ ->
						setting = if (which == Strength.Sharpen) setting.copy(sharpen = value) else setting.copy(deband = value)
					},
				)
			}
		}
	}
}

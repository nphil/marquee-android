package com.nphil.marquee.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.downloads.DownloadChoice
import com.nphil.marquee.downloads.DownloadEntry
import com.nphil.marquee.downloads.DownloadPhase
import com.nphil.marquee.downloads.DownloadScope
import com.nphil.marquee.downloads.DownloadTitle
import com.nphil.marquee.downloads.DownloadWatch
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucentTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveDownloadsTest {
	@get:Rule val rule = createComposeRule()

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val themes = Themes(context, ThemeCatalog.parse(context.assets.open("themes.json").bufferedReader().use { it.readText() }))
	private val typography = lucentTypography(context.assets)

	@Test
	fun wideGridCapsCardsAndANarrowPaneInTheSameWindowStacksThem() {
		val ready = entry("Sintel", DownloadPhase.Ready)
		val active = entry("Big Buck Bunny", DownloadPhase.Downloading)
		var pane by mutableStateOf(DpSize(1280.dp, 440.dp))
		var played: String? = null
		rule.setContent {
			ThemedWindow {
				Box(Modifier.size(pane)) { Library(listOf(ready, active), onPlay = { played = it.id }) }
			}
		}

		val first = rule.onNodeWithText(ready.title.title, useUnmergedTree = true)
		val second = rule.onNodeWithText(active.title.title, useUnmergedTree = true)
		assertTrue(first.bounds().right < second.bounds().left)
		val play = rule.onNode(hasText(ready.title.title) and hasClickAction())
		val cardBounds = play.getUnclippedBoundsInRoot()
		assertTrue(cardBounds.right - cardBounds.left <= 540.dp)
		play.performClick()
		rule.runOnIdle {
			assertEquals(ready.id, played)
			pane = DpSize(320.dp, 640.dp)
		}

		val compactCard = play.getUnclippedBoundsInRoot()
		assertTrue(compactCard.right - compactCard.left <= 289.dp)
		assertEquals(first.bounds().left, second.bounds().left, 1f)
		assertTrue(first.bounds().bottom < second.bounds().top)
		play.assertIsDisplayed()
		rule.onNodeWithText("Saved offline", useUnmergedTree = true).assertFits()
	}

	@Test
	fun largeTextFitsAndRemoveConfirmationSurvivesRestoration() {
		val active = entry("The Long Journey Across the Mountains", DownloadPhase.Converting).copy(conversionFraction = .27)
		var pane by mutableStateOf(DpSize(320.dp, 480.dp))
		var paused: String? = null
		var removed: String? = null
		val restoration = StateRestorationTester(rule)
		restoration.setContent {
			ThemedWindow(mode = ThemeMode.Light, fontScale = 2f) {
				Box(Modifier.size(pane)) {
					Library(listOf(active), onPause = { paused = it.id }, onRemove = { removed = it.id })
				}
			}
		}

		val title = rule.onNodeWithText(active.title.title, useUnmergedTree = true).assertFits()
		val status = rule.onNodeWithText("Preparing on Plex · 27%", useUnmergedTree = true).assertFits()
		val pause = rule.onNodeWithContentDescription("Pause download").assertIsDisplayed()
		assertTrue(pause.bounds().bottom < title.bounds().top)
		assertTrue(title.bounds().bottom <= status.bounds().top)
		pause.performClick()
		rule.runOnIdle { assertEquals(active.id, paused) }

		rule.onNodeWithContentDescription("Remove download").performClick()
		rule.onNodeWithText("Remove download?").assertIsDisplayed()
		rule.runOnIdle { pane = DpSize(1000.dp, 400.dp) }
		restoration.emulateSavedInstanceStateRestore()
		rule.onNodeWithText("Remove download?").assertIsDisplayed()
		rule.onNodeWithText("Remove").performClick()
		rule.runOnIdle { assertEquals(active.id, removed) }
	}

	@Test
	fun qualityOptionsReflowForWidthAndFontScaleWithoutLosingThePick() {
		val refusal = "Plex cannot convert this version for offline viewing"
		val options = listOf(
			DownloadChoice("original", "Original", resolution = "1080p"),
			DownloadChoice("high", "High", resolution = "720p"),
			DownloadChoice("medium", "Medium", refusal = refusal),
		)
		var width by mutableStateOf(480.dp)
		var fontScale by mutableFloatStateOf(1f)
		rule.setContent {
			ThemedWindow(fontScale = fontScale) {
				var chosen by rememberSaveable { mutableStateOf("original") }
				Box(Modifier.width(width)) { DownloadQualityOptions(options, chosen) { chosen = it } }
			}
		}

		val original = rule.onNodeWithText("Original", useUnmergedTree = true)
		val high = rule.onNodeWithText("High", useUnmergedTree = true)
		val highChoice = rule.onNodeWithText("High")
		assertTrue(original.bounds().right < high.bounds().left)
		highChoice.performClick().assertIsSelected()
		rule.runOnIdle { width = 272.dp }
		assertTrue(original.bounds().bottom < high.bounds().top)
		highChoice.assertIsSelected()
		rule.runOnIdle { width = 480.dp; fontScale = 2f }
		assertTrue(original.bounds().bottom < high.bounds().top)
		highChoice.assertIsSelected()
		rule.onNodeWithText("Medium").assertIsNotEnabled()
		rule.onNodeWithText(refusal, useUnmergedTree = true).assertFits()
	}

	@Composable
	private fun ThemedWindow(mode: ThemeMode = ThemeMode.Dark, fontScale: Float = 1f, content: @Composable () -> Unit) {
		val id = if (mode == ThemeMode.Dark) LucentTheme.DEFAULT_DARK else LucentTheme.DEFAULT_LIGHT
		DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(1280.dp, 800.dp))) {
			DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
				// The window override supplies fixed minima as well as maxima; the pane needs loose minima to resize.
				MarqueeTheme(themes.resolve(id, mode), typography) { Box { content() } }
			}
		}
	}

	@Composable
	private fun Library(
		records: List<DownloadEntry>,
		onPlay: (DownloadEntry) -> Unit = {},
		onPause: (DownloadEntry) -> Unit = {},
		onRemove: (DownloadEntry) -> Unit = {},
	) {
		DownloadsContent(
			records, metered = false, verifyProfile = false, padding = PaddingValues(Space.inset),
			poster = { error("Fixture has no art") }, onPlay = onPlay, onPause = onPause, onResume = {}, onRemove = onRemove,
		)
	}

	private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

	private fun SemanticsNodeInteraction.assertFits(): SemanticsNodeInteraction {
		assertIsDisplayed()
		val result = mutableListOf<TextLayoutResult>()
		performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(result)) }
		val layout = result.single()
		// Compose reconstructs the semantic paragraph at the parent's max width, even for short labels.
		// Check its actual lines against the measured text box instead of comparing the paragraph widths.
		assertFalse(layout.didOverflowHeight)
		assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
		for (line in 0 until layout.lineCount) {
			assertFalse(layout.isLineEllipsized(line))
			assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
		}
		return this
	}

	private fun entry(title: String, phase: DownloadPhase) = DownloadEntry(
		id = title, scope = DownloadScope("server", "profile"), title = DownloadTitle(title, title, 60_000),
		version = 0, choice = DownloadChoice("original", "Original", resolution = "1080p"),
		isAnime = false, countries = emptyList(), watch = DownloadWatch(0, false), queuedAt = 0, phase = phase,
		downloaded = 50, length = 100,
	)
}

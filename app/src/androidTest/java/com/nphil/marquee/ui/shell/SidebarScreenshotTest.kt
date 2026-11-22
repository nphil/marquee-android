package com.nphil.marquee.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.R
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ResolvedTheme
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.theme.lucentTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sidebar's behaviour, and its review screenshots: closed, open, part-way through opening, and on a light
 * theme. The sidebar sits in a column the height of the window with a strip of placeholder content beside it, so
 * the screenshots fit whatever window the emulator has.
 */
@RunWith(AndroidJUnit4::class)
class SidebarScreenshotTest {
	@get:Rule
	val rule = createComposeRule()

	private val expanded = mutableStateOf(false)
	private val selected = mutableStateOf("library")

	@Test
	fun closedThenOpen() {
		show()
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-collapsed")

		brand().performClick()
		rule.waitForIdle()
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-expanded")
	}

	@Test
	fun partWayThroughOpening() {
		// The clock stands still until told otherwise, so the frame captured is the one asked for.
		rule.mainClock.autoAdvance = false
		show()
		// The first frame starts the effects that tell a toggle from the first composition.
		rule.mainClock.advanceTimeByFrame()
		val closed = sidebarWidth()

		brand().performClick()
		rule.mainClock.advanceTimeBy(MidAnimationMillis)
		val moving = sidebarWidth()
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-animating")

		rule.mainClock.autoAdvance = true
		rule.waitForIdle()
		val open = sidebarWidth()
		// The start inset is in all three widths; only the difference between rail and pane is fixed.
		assertEquals(OpenGrowth, (open - closed).value, 0.5f)
		assertTrue("$moving is not between $closed and $open", moving > closed && moving < open)
	}

	@Test
	fun lightTheme() {
		expanded.value = true
		show(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-expanded-light")
	}

	@Test
	fun brandIsTheOnlyToggleAndNamesWhatItWillDo() {
		show()
		brand()
			.assertHasClickAction()
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
			.assert(hasClickLabel("Expand sidebar"))
			.assertWidthIsAtLeast(Size.target)
			.assertHeightIsAtLeast(Size.target)
		rule.onAllNodes(hasClickLabel("Expand sidebar") or hasClickLabel("Collapse sidebar")).assertCountEquals(1)

		brand().performClick()
		rule.waitForIdle()
		assertTrue(expanded.value)
		brand().assert(hasClickLabel("Collapse sidebar"))

		brand().performClick()
		rule.waitForIdle()
		assertFalse(expanded.value)
		brand().assert(hasClickLabel("Expand sidebar"))
	}

	@Test
	fun destinationsAreNamedInTheRailAndReportSelection() {
		show()
		for (destination in destinations + footer) {
			item(destination.key)
				.assertHasClickAction()
				.assertContentDescriptionEquals(destination.label)
				.assertWidthIsAtLeast(Size.target)
				.assertHeightIsAtLeast(Size.target)
		}
		item("library").assertIsSelected()
		item("watchlist").assertIsNotSelected().performClick()
		assertEquals("watchlist", selected.value)
		item("watchlist").assertIsSelected()
		item("library").assertIsNotSelected()

		item("settings").performClick()
		assertEquals("settings", selected.value)
	}

	@Test
	fun firstCompositionIsAlreadyAtItsWidth() {
		expanded.value = true
		rule.mainClock.autoAdvance = false
		show()
		val first = sidebarWidth()
		rule.mainClock.advanceTimeBy(1_000)
		assertEquals(first, sidebarWidth())
	}

	@Test
	fun markPullsApartThenSettlesBack() {
		val toggles = mutableIntStateOf(0)
		val (resolved, typography) = resolveTheme(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.mainClock.autoAdvance = false
		rule.setContent {
			MarqueeTheme(resolved, typography) {
				Box(Modifier.testTag(Mark).background(lucent.canvas).padding(Space.s4)) {
					MarqueeMark(MarkBox, toggles = toggles.intValue, expanding = true)
				}
			}
		}
		val assembled = mark().pixels()
		rule.mainClock.advanceTimeBy(1_000)
		assertTrue("the mark moved on its first composition", assembled.contentEquals(mark().pixels()))

		rule.runOnUiThread { toggles.intValue++ }
		// Under a paused main loop the change only reaches the composition once the loop has run; do that first.
		rule.waitForIdle()
		rule.mainClock.advanceTimeBy(MidAnimationMillis)
		assertFalse("the mark did not move", assembled.contentEquals(mark().pixels()))

		rule.mainClock.advanceTimeBy(2_000)
		assertTrue("the mark did not settle back", assembled.contentEquals(mark().pixels()))
	}

	private fun show(id: String = LucentTheme.DEFAULT_DARK, mode: ThemeMode = ThemeMode.Dark) {
		val (resolved, typography) = resolveTheme(id, mode)
		rule.setContent {
			MarqueeTheme(resolved, typography) {
				Row(Modifier.fillMaxHeight().background(lucent.canvas).testTag(Shell)) {
					MarqueeSidebar(
						destinations = destinations,
						selected = selected.value,
						onSelect = { selected.value = it },
						expanded = expanded.value,
						onToggle = { expanded.value = !expanded.value },
						footer = footer,
					)
					PlaceholderContent()
				}
			}
		}
	}

	private fun brand() = rule.onNodeWithTag(SidebarTags.Brand)

	private fun item(key: String) = rule.onNodeWithTag(SidebarTags.item(key))

	private fun mark() = rule.onNodeWithTag(Mark)

	private fun sidebarWidth(): Dp = rule.onNodeWithTag(SidebarTags.Root).getUnclippedBoundsInRoot().width

	private fun hasClickLabel(label: String) = SemanticsMatcher("has click label \"$label\"") {
		it.config.getOrNull(SemanticsActions.OnClick)?.label == label
	}

	private fun SemanticsNodeInteraction.pixels(): IntArray {
		val image = captureToImage()
		return IntArray(image.width * image.height).also { image.readPixels(it) }
	}

	// Built like the app builds it, from the app's own assets rather than the test APK's.
	private fun resolveTheme(id: String, mode: ThemeMode): Pair<ResolvedTheme, Typography> {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val catalog = ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() })
		return Themes(context, catalog).resolve(id, mode) to lucentTypography(context.assets)
	}

	private companion object {
		const val Shell = "shell"
		const val Mark = "mark"

		/** Long enough for the pane to be most of the way open while its labels and the mark are still moving. */
		const val MidAnimationMillis = 150L

		/** The pane's 236 dp less the rail's 72 dp. */
		const val OpenGrowth = 164f

		val MarkBox = 96.dp

		val destinations = listOf(
			SidebarDestination("library", "Library", R.drawable.ic_video_library, R.drawable.ic_video_library_filled),
			SidebarDestination("watchlist", "Watchlist", R.drawable.ic_bookmark, R.drawable.ic_bookmark_filled),
			SidebarDestination("live", "Live TV", R.drawable.ic_tv, R.drawable.ic_tv),
			SidebarDestination("profiles", "Profiles", R.drawable.ic_account_circle, R.drawable.ic_account_circle),
		)
		val footer = listOf(
			SidebarDestination("settings", "Settings", R.drawable.ic_settings, R.drawable.ic_settings_filled),
		)
	}
}

@Composable
private fun PlaceholderContent() {
	Column(
		Modifier.width(200.dp).fillMaxHeight().padding(Space.s4),
		verticalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		repeat(3) {
			Box(
				Modifier
					.fillMaxWidth()
					.height(96.dp)
					.background(lucent.glass, MaterialTheme.shapes.medium)
					.border(1.dp, lucent.edge, MaterialTheme.shapes.medium),
			)
		}
	}
}

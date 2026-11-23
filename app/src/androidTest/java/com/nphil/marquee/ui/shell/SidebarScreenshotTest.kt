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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
import androidx.compose.ui.test.center
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.R
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
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
 * theme, in a tablet-sized window. The sidebar sits in a column the height of the window with a strip of
 * placeholder content beside it, so the screenshots fit whatever screen the emulator has.
 */
@RunWith(AndroidJUnit4::class)
class SidebarScreenshotTest {
	@get:Rule
	val rule = createComposeRule()

	private var sizing = SidebarSize(expanded = false, open = OPEN_DP)
	private var resized: Pair<Boolean, Float>? = null
	private val selected = mutableStateOf("library")
	private val profile = mutableStateOf(
		PlexHomeUser(1, "jordan", "Jordan", isAdmin = true, isProtected = false, isRestricted = false, isGuest = false),
	)

	@Test
	fun closedThenOpen() {
		show()
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-tablet-closed")

		brand().performClick()
		rule.waitForIdle()
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-tablet-open")
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
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-tablet-opening")

		rule.mainClock.autoAdvance = true
		rule.waitForIdle()
		val open = sidebarWidth()
		// The start inset is in all three widths; only the difference between rail and pane is fixed.
		assertEquals(OpenGrowth, (open - closed).value, 0.5f)
		assertTrue("$moving is not between $closed and $open", moving > closed && moving < open)
	}

	@Test
	fun lightTheme() {
		sizing = SidebarSize(expanded = true, open = OPEN_DP)
		show(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-tablet-open-light")
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
		assertTrue(sizing.expanded)
		brand().assert(hasClickLabel("Collapse sidebar"))

		brand().performClick()
		rule.waitForIdle()
		assertFalse(sizing.expanded)
		brand().assert(hasClickLabel("Expand sidebar"))
	}

	@Test
	fun draggingTheHandleSetsTheWidthAndReportsIt() {
		sizing = SidebarSize(expanded = true, open = OPEN_DP)
		show()
		handle().performTouchInput { swipe(center, center + Offset(60.dp.toPx(), 0f)) }
		rule.waitForIdle()
		assertEquals(OPEN_DP + 60f, sizing.open, 0.5f)
		assertEquals(true to sizing.open, resized)
		rule.onNodeWithTag(Shell).saveScreenshot("sidebar-tablet-resized")
	}

	@Test
	fun draggingTheHandleNarrowSnapsToTheRailAndKeepsTheOpenWidth() {
		sizing = SidebarSize(expanded = true, open = OPEN_DP)
		show()
		handle().performTouchInput { swipe(center, center - Offset(200.dp.toPx(), 0f)) }
		rule.waitForIdle()
		assertFalse(sizing.expanded)
		assertEquals(RAIL_DP, sizing.width, 0.5f)
		assertEquals(false to OPEN_DP, resized)
	}

	@Test
	fun handleIsAnAdjustableControlForAssistiveServices() {
		sizing = SidebarSize(expanded = true, open = OPEN_DP)
		show()
		handle().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(OPEN_DP, sizing.range)))
		handle().performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
		rule.waitForIdle()
		assertEquals(300f, sizing.open, 0.5f)
	}

	@Test
	fun destinationsAreNamedInTheRailAndReportSelection() {
		show()
		for (destination in destinations) {
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

		item(SETTINGS_KEY).assertSettingsTarget(Role.Tab).performClick()
		assertEquals(SETTINGS_KEY, selected.value)
	}

	@Test
	fun settingsKeepsItsNameAndTargetAfterTheProfileChanges() {
		show()
		val settings = item(SETTINGS_KEY).assertSettingsTarget(Role.Tab)
		val beforeSwitch = settings.pixels()

		rule.runOnIdle { profile.value = profile.value.copy(id = 2, uuid = "maya", title = "Maya") }
		assertFalse("Settings still shows the previous profile initial", beforeSwitch.contentEquals(settings.pixels()))

		brand().performClick()
		settings.assertSettingsTarget(Role.Tab).performClick()
		settings.assertIsSelected()
	}

	@Test
	fun compactSettingsStillOpensSettings() {
		val (resolved, typography) = resolveTheme(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		var clicked = false
		rule.setContent {
			MarqueeTheme(resolved, typography) {
				CompactSettingsButton(profile.value) { clicked = true }
			}
		}

		rule.onNodeWithContentDescription("Settings").assertSettingsTarget(Role.Button).performClick()
		rule.runOnIdle { assertTrue(clicked) }
	}

	@Test
	fun firstCompositionIsAlreadyAtItsWidth() {
		sizing = SidebarSize(expanded = true, open = OPEN_DP)
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
			DeviceWindow(Device.Tablet) {
				MarqueeTheme(resolved, typography) {
					Row(Modifier.fillMaxHeight().background(lucent.canvas).testTag(Shell)) {
						MarqueeSidebar(
							destinations = destinations,
							selected = selected.value,
							onSelect = { selected.value = it },
							sizing = sizing,
							onResize = { expanded, open -> resized = expanded to open },
							settingsProfile = profile.value,
						)
						PlaceholderContent()
					}
				}
			}
		}
	}

	private fun brand() = rule.onNodeWithTag(SidebarTags.Brand)

	private fun handle() = rule.onNodeWithContentDescription("Resize sidebar")

	private fun item(key: String) = rule.onNodeWithTag(SidebarTags.item(key))

	private fun mark() = rule.onNodeWithTag(Mark)

	private fun SemanticsNodeInteraction.assertSettingsTarget(role: Role) = assertHasClickAction()
		.assertContentDescriptionEquals("Settings")
		.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))
		.assertWidthIsAtLeast(Size.target)
		.assertHeightIsAtLeast(Size.target)

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

		/** The pane's width less the rail's. */
		const val OpenGrowth = OPEN_DP - RAIL_DP

		val MarkBox = 96.dp

		val destinations = listOf(
			SidebarDestination("library", "Library", R.drawable.ic_video_library, R.drawable.ic_video_library_filled),
			SidebarDestination("watchlist", "Watchlist", R.drawable.ic_bookmark, R.drawable.ic_bookmark_filled),
			SidebarDestination("live", "Live TV", R.drawable.ic_tv, R.drawable.ic_tv),
			SidebarDestination("profiles", "Profiles", R.drawable.ic_account_circle, R.drawable.ic_account_circle),
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

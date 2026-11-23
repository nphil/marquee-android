package com.nphil.marquee.ui.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.testing.saveWindow
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.tvremote.SavedTv
import com.nphil.marquee.tvremote.TvStatus
import com.nphil.marquee.ui.MarqueeApp
import com.nphil.marquee.ui.shell.SETTINGS_KEY
import com.nphil.marquee.ui.shell.SidebarTags
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

private const val Orb = "tv-remote-orb"
private const val Panel = "tv-remote-panel"

private val DpRect.centerX get() = (left + right).value / 2
private val DpRect.centerY get() = (top + bottom).value / 2

@RunWith(AndroidJUnit4::class)
class TvRemoteScreenshotTest {
	@get:Rule(order = 0) val fake = SignedInToFake()
	@get:Rule(order = 1) val rule = createComposeRule()

	private val tv = SavedTv("5a1c0ffee0ddba11", "Living Room TV", "", "fixture", "", "127.0.0.1", 18733, pairedAt = 0.0)
	private val status = TvStatus(tv, "connected", volume = 30.5, volumeMax = 72.5, keyboard = true, pointer = true)

	@Test fun orbDocksBesideTheProfileButtonAndFollowsTheSidebar() {
		val container = fake.container
		val wasEnabled = container.tvRemote.state.value.enabled
		val device = mutableStateOf(Device.Tablet)
		fake.prefer { setSidebarExpanded(true) }
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		try {
			rule.runOnIdle { container.tvRemote.enable(true) }
			rule.setContent {
				DeviceWindow(device.value) {
					MarqueeTheme(theme, container.typography) { MarqueeApp(container, ThemeChoice(), onTheme = {}) }
				}
			}
			rule.waitUntilAtLeastOneExists(hasTestTag(Orb), 15_000)
			rule.waitForIdle()
			assertBesideProfile()
			rule.saveWindow("tv-remote-orb-sidebar-expanded")
			rule.onNodeWithTag(SidebarTags.Brand).performClick()
			rule.waitForIdle()
			assertAboveProfile()
			rule.saveWindow("tv-remote-orb-sidebar-collapsed")
			rule.runOnIdle { device.value = Device.Phone }
			rule.waitUntilAtLeastOneExists(hasTestTag(Orb), 15_000)
			rule.waitForIdle()
			// The phone's toolbar no longer carries a second entry: the floating button is the only one.
			rule.onAllNodesWithContentDescription("TV Remote").assertCountEquals(1)
			rule.saveWindow("tv-remote-orb-phone")
		} finally {
			rule.runOnIdle { container.tvRemote.enable(wasEnabled) }
		}
	}

	@Test fun panelLaysOutForItsShapeAndSendsKeys() {
		val dark = fake.container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		val light = fake.container.themes.resolve(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		val size = mutableStateOf(DpSize(380.dp, 660.dp))
		val night = mutableStateOf(true)
		val mode = mutableStateOf(PadMode.Dpad)
		val commands = mutableListOf<RemoteRequest>()
		rule.setContent {
			DeviceWindow(Device.Tablet) {
				MarqueeTheme(if (night.value) dark else light, fake.container.typography) {
					Box(Modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
						PanelFixture(size.value, mutableStateOf(AllModules), mutableStateOf(false), mode, { commands.add(it) })
					}
				}
			}
		}
		rule.onNodeWithText("Living Room TV").assertIsDisplayed()
		rule.onNodeWithText("Connected").assertIsDisplayed()

		// Tall: the pad above the strips. A tap on the pad's top wedge is an Up.
		val column = rule.onNodeWithTag("tv-dpad").getUnclippedBoundsInRoot()
		assertTrue(column.bottom <= rule.onNodeWithContentDescription("TV Back").getUnclippedBoundsInRoot().top)
		rule.onNodeWithTag("tv-dpad").performTouchInput { click(Offset(width / 2f, height * 0.12f)) }
		rule.runOnIdle { assertEquals(listOf(RemoteRequest(key = "up")), commands) }
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-column-dark")

		rule.onNodeWithContentDescription("Joystick").performClick()
		rule.onNodeWithTag("tv-joystick").assertIsDisplayed()
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-joystick-dark")
		rule.onNodeWithContentDescription("Touchpad").performClick()
		rule.onNodeWithTag("tv-touchpad").assertIsDisplayed()
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-touchpad-dark")
		rule.onNodeWithContentDescription("Directional pad").performClick()

		// Wide: the same keys move beside the pad.
		rule.runOnIdle {
			size.value = DpSize(700.dp, 340.dp)
			night.value = false
		}
		rule.onNodeWithTag("tv-dpad").assertIsDisplayed()
		val pad = rule.onNodeWithTag("tv-dpad").getUnclippedBoundsInRoot()
		assertTrue(pad.right <= rule.onNodeWithContentDescription("Volume up").getUnclippedBoundsInRoot().left)
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-row-light")

		// Small: the pad alone.
		rule.runOnIdle { size.value = DpSize(232.dp, 232.dp) }
		rule.onNodeWithTag("tv-dpad").assertIsDisplayed()
		rule.onAllNodesWithContentDescription("TV Home").assertCountEquals(0)
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-compact-light")
	}

	@Test fun holdingAnEmptyAreaEditsWhichControlsShow() {
		val dark = fake.container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		val modules = mutableStateOf(AllModules)
		val editing = mutableStateOf(false)
		rule.setContent {
			DeviceWindow(Device.Tablet) {
				MarqueeTheme(dark, fake.container.typography) {
					Box(Modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
						PanelFixture(DpSize(380.dp, 660.dp), modules, editing, mutableStateOf(PadMode.Dpad), {})
					}
				}
			}
		}
		rule.onNodeWithTag(Panel).performTouchInput {
			down(Offset(6f, height / 2f))
			advanceEventTime(800)
			up()
		}
		rule.runOnIdle { assertTrue(editing.value) }
		rule.onNodeWithContentDescription("Done").assertIsDisplayed()
		rule.onNodeWithContentDescription("Hide Volume").performClick()
		rule.runOnIdle { assertFalse(RemoteModule.Volume in modules.value) }
		// A hidden element waits in the tray at the bottom, to be added back.
		rule.onNodeWithText("Volume").assertIsDisplayed()
		rule.onNodeWithTag(Panel).saveScreenshot("tv-remote-panel-editing-dark")
		rule.onNodeWithContentDescription("Done").performClick()
		rule.runOnIdle { assertFalse(editing.value) }
		rule.onAllNodesWithContentDescription("Volume up").assertCountEquals(0)
	}

	/** The panel as the floating remote draws it, without the button it grows from. */
	@Composable
	private fun PanelFixture(
		size: DpSize, modules: MutableState<Set<RemoteModule>>, editing: MutableState<Boolean>, mode: MutableState<PadMode>,
		input: (RemoteRequest) -> Unit,
	) {
		val shape = RoundedCornerShape(Radius.sheet)
		val actions = RemoteFaceActions({}, {}, {}, {}, {}, {}, {})
		val chrome = RemoteChrome("TV Remote", true, false, false, null, tv, status, {}, actions)
		Box(Modifier.testTag(Panel).size(size).clip(shape).background(lucent.sheet).border(1.dp, lucent.edge, shape)) {
			RemotePanelFrame(
				chrome, {}, size, modules.value, { modules.value = it }, mode.value, { mode.value = it }, editing.value,
				{ editing.value = it }, {}, backEnabled = false, input = input, motion = PanelMotion({}, { _, _ -> }, {}),
			)
		}
	}

	private fun assertBesideProfile() {
		val orb = rule.onNodeWithTag(Orb).getUnclippedBoundsInRoot()
		val row = rule.onNodeWithTag(SidebarTags.item(SETTINGS_KEY)).getUnclippedBoundsInRoot()
		val sidebar = rule.onNodeWithTag(SidebarTags.Root).getUnclippedBoundsInRoot()
		assertTrue("centred on the profile row: ${orb.centerY} vs ${row.centerY}", abs(orb.centerY - row.centerY) <= 4f)
		assertTrue("inside the sidebar, after the avatar", orb.right <= sidebar.right && orb.left > row.left)
	}

	private fun assertAboveProfile() {
		val orb = rule.onNodeWithTag(Orb).getUnclippedBoundsInRoot()
		val row = rule.onNodeWithTag(SidebarTags.item(SETTINGS_KEY)).getUnclippedBoundsInRoot()
		assertTrue("directly above the profile row", orb.bottom <= row.top + 1.dp && row.top - orb.bottom <= 16.dp)
		assertTrue("over the avatar's line: ${orb.centerX} vs ${row.centerX}", abs(orb.centerX - row.centerX) <= 4f)
	}
}

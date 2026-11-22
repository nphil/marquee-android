package com.nphil.marquee.ui.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvRemoteScreenshotTest {
	@get:Rule(order = 0) val fake = SignedInToFake()
	@get:Rule(order = 1) val rule = createComposeRule()

	@Test fun remoteImmediatelyPrecedesSettingsInBothSidebarStatesAndHasOnePhoneEntry() {
		val container = fake.container
		val wasEnabled = container.tvRemote.state.value.enabled
		val device = mutableStateOf(Device.Tablet)
		fake.prefer { setSidebarExpanded(true) }
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		val entry = SidebarTags.item("tv-remote")
		try {
			rule.runOnIdle { container.tvRemote.enable(true) }
			rule.setContent {
				DeviceWindow(device.value) {
					MarqueeTheme(theme, container.typography) { MarqueeApp(container, ThemeChoice(), onTheme = {}) }
				}
			}
			rule.waitUntilAtLeastOneExists(hasTestTag(entry), 15_000)
			assertFooter(entry)
			rule.saveWindow("tv-remote-sidebar-expanded")
			rule.onNodeWithTag(SidebarTags.Brand).performClick()
			assertFooter(entry)
			rule.saveWindow("tv-remote-sidebar-collapsed")
			rule.runOnIdle { device.value = Device.Phone }
			rule.waitUntilAtLeastOneExists(hasTestTag("tv-remote-entry"), 15_000)
			rule.onNodeWithTag(entry).assertDoesNotExist()
			rule.onAllNodesWithContentDescription("TV Remote").assertCountEquals(1)
			rule.saveWindow("tv-remote-home-compact")
		} finally {
			rule.runOnIdle { container.tvRemote.enable(wasEnabled) }
		}
	}

	@Test fun pairedFaceAdaptsToWidthAndSendsDirectionalCommand() {
		val container = fake.container
		val device = mutableStateOf(Device.Phone)
		val dark = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		val light = container.themes.resolve(LucentTheme.DEFAULT_LIGHT, ThemeMode.Light)
		val tv = SavedTv("5a1c0ffee0ddba11", "Living Room TV", "", "fixture", "", "127.0.0.1", 18733, pairedAt = 0.0)
		val status = TvStatus(tv, "connected", volume = 30.5, volumeMax = 72.5, keyboard = true, pointer = true)
		val commands = mutableListOf<RemoteRequest>()
		rule.setContent {
			val theme = if (device.value == Device.Phone) dark else light
			DeviceWindow(device.value) {
				MarqueeTheme(theme, container.typography) {
					Box(Modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
						RemotePanelBody("TV Remote", onBack = {}) {
							RemoteFace(tv, status, { commands.add(it) }, onPower = {}, onTvs = {}, onPair = {},
								onKeyboard = {}, onMore = {}, onReconnect = {})
						}
					}
				}
			}
		}
		rule.onNodeWithText("Living Room TV").assertIsDisplayed()
		rule.onNodeWithContentDescription("Up").performClick()
		rule.runOnIdle { assertEquals(listOf(RemoteRequest(key = "up")), commands) }
		rule.onNodeWithTag("tv-remote-panel").saveScreenshot("tv-remote-paired-compact-dark")
		rule.runOnIdle { device.value = Device.Tablet }
		val panel = rule.onNodeWithTag("tv-remote-panel")
		val bounds = panel.getUnclippedBoundsInRoot()
		assertTrue(bounds.right - bounds.left <= 549.dp)
		rule.onNodeWithText("Connected").assertIsDisplayed()
		panel.saveScreenshot("tv-remote-paired-expanded-light")
	}

	private fun assertFooter(entry: String) {
		val remote = rule.onNodeWithTag(entry).getUnclippedBoundsInRoot()
		val settings = rule.onNodeWithTag(SidebarTags.item(SETTINGS_KEY)).getUnclippedBoundsInRoot()
		assertTrue(settings.top - remote.bottom in 0.dp..4.dp)
		assertEquals(remote.left, settings.left)
	}
}

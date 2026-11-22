package com.nphil.marquee.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The windows the review screenshots are taken in, whatever screen the emulator has. [file] is the word that names
 * the window in a screenshot's file name. The app tells a phone from a tablet by width alone, so a portrait tablet
 * still gets the sidebar.
 */
enum class Device(val file: String, val size: DpSize) {
	Phone("phone", DpSize(411.dp, 891.dp)),
	Tablet("tablet", DpSize(1280.dp, 800.dp)),
	TabletPortrait("tablet-portrait", DpSize(800.dp, 1280.dp)),
	;

	override fun toString() = file
}

private const val WindowTag = "review.window"

/**
 * Lays [content] out in a window of [device]'s size: the layout sees that many dp, at a density that fits the
 * emulator's screen without clipping. What [saveWindow] captures is exactly this window.
 */
@Composable
fun DeviceWindow(device: Device, content: @Composable () -> Unit) {
	DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(device.size)) {
		Box(Modifier.fillMaxSize().testTag(WindowTag)) { content() }
	}
}

/** The window [DeviceWindow] laid out, for a test that captures it frame by frame. */
fun ComposeTestRule.deviceWindow(): SemanticsNodeInteraction = onNodeWithTag(WindowTag)

/** Saves the window [DeviceWindow] laid out as `<name>.png` among the review screenshots, once the frame has settled. */
fun ComposeTestRule.saveWindow(name: String) {
	waitForIdle()
	deviceWindow().saveScreenshot(name)
}

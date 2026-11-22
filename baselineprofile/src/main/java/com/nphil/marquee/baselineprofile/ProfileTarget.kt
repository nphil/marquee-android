package com.nphil.marquee.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The build the profile is recorded against: nonMinifiedRelease, whose ProfilingApplication answers from the iPhone
 * app's fixtures and starts signed in to the fixture account, so every journey begins on Home.
 */
object ProfileTarget {
	val packageName: String
		get() = InstrumentationRegistry.getArguments().getString("targetAppId")
			?.takeIf { it.isNotEmpty() }
			?: error("targetAppId is passed by baselineprofile/build.gradle.kts")

	/** Waits for the poster on Home, scrolling Home for it when it is below the first screen, and opens its page. */
	fun openFromHome(device: UiDevice, poster: BySelector) {
		repeat(5) {
			device.wait(Until.findObject(poster), 8_000)?.let {
				it.click()
				check(device.wait(Until.hasObject(By.desc("Back")), 10_000)) { "the title page did not open" }
				return
			}
			scroll(device, down = true)
		}
		error("no poster on Home matched $poster")
	}

	/** One screenful, the finger moving up to reveal what is below. */
	fun scroll(device: UiDevice, down: Boolean) {
		val x = device.displayWidth / 2
		val low = device.displayHeight * 3 / 4
		val high = device.displayHeight / 4
		if (down) device.swipe(x, low, x, high, 24) else device.swipe(x, high, x, low, 24)
		device.waitForIdle()
	}
}

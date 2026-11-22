package com.nphil.marquee.baselineprofile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The build the profile is recorded against: nonMinifiedRelease, whose ProfilingApplication answers from the iPhone
 * app's fixtures and starts signed in to the fixture account, so every journey begins on Home.
 */
object ProfileTarget {
	val packageName: String
		get() = InstrumentationRegistry.getArguments().getString("targetAppId")
			?.takeIf { it.isNotEmpty() }
			?: error("targetAppId is passed by baselineprofile/build.gradle.kts")

	/**
	 * Waits for the poster on Home, scrolling Home for it when it is below the first screen, and opens its page. A tap
	 * that opens nothing is repeated once.
	 */
	fun openFromHome(device: UiDevice, poster: BySelector) {
		repeat(5) {
			if (device.wait(Until.hasObject(poster), 8_000)) {
				repeat(2) {
					device.findObject(poster)?.click()
					if (device.wait(Until.hasObject(By.desc("Back")), 4_000)) return
				}
				val onScreen = device.findObjects(By.desc(Pattern.compile(".+"))).mapNotNull { it.contentDescription }
				error("the title page did not open; on screen: $onScreen")
			}
			scroll(device, down = true)
		}
		error("no poster on Home matched $poster")
	}

	/** One screenful, the finger moving up to reveal what is below, then a wait for the fling to end. */
	fun scroll(device: UiDevice, down: Boolean) {
		val x = device.displayWidth / 2
		val low = device.displayHeight * 3 / 4
		val high = device.displayHeight / 4
		if (down) device.swipe(x, low, x, high, 24) else device.swipe(x, high, x, low, 24)
		device.waitForIdle()
		// waitForIdle returns while the list is still flinging, and a tap on a flinging list only stops it:
		// on the emulator the first tap after a scroll opened nothing.
		SystemClock.sleep(FLING_MILLIS)
	}

	private const val FLING_MILLIS = 1_000L
}

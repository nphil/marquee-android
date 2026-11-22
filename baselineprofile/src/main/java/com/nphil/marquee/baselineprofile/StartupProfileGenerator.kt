package com.nphil.marquee.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the classes and methods a cold start runs up to Home, signed in as the profiling build always is. */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
	@get:Rule
	val rule = BaselineProfileRule()

	@Test
	fun startup() {
		rule.collect(packageName = ProfileTarget.packageName, includeInStartupProfile = true) {
			pressHome()
			startActivityAndWait()
			check(device.wait(Until.hasObject(By.text("Home")), 15_000)) {
				"Home did not appear"
			}
		}
	}
}

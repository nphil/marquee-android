package com.nphil.marquee.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the classes and methods a cold start runs up to the first interactive screen. */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
	@get:Rule
	val rule = BaselineProfileRule()

	@Test
	fun startup() {
		val packageName = InstrumentationRegistry.getArguments().getString("targetAppId")
			?.takeIf { it.isNotEmpty() }
			?: error("targetAppId is passed by baselineprofile/build.gradle.kts")
		rule.collect(packageName = packageName, includeInStartupProfile = true) {
			pressHome()
			startActivityAndWait()
			// A fresh install opens on sign-in.
			check(device.wait(Until.hasObject(By.text("Sign in")), 10_000)) {
				"the sign-in screen did not appear"
			}
		}
	}
}

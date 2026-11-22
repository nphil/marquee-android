package com.nphil.marquee.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records opening a title from Home and scrolling its page: a movie's, then a show's with a swipe on one episode and
 * the episode's own page. The build answers from the iPhone app's fixtures, see [ProfileTarget].
 */
@RunWith(AndroidJUnit4::class)
class DetailProfileGenerator {
	@get:Rule
	val rule = BaselineProfileRule()

	private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

	@Test
	fun moviePage() = journey {
		ProfileTarget.openFromHome(device, By.descContains("Sintel"))
		repeat(4) { ProfileTarget.scroll(device, down = true) }
		repeat(4) { ProfileTarget.scroll(device, down = false) }
		device.pressBack()
	}

	@Test
	fun showPage() = journey {
		// Only a show's poster says how many episodes are unwatched.
		ProfileTarget.openFromHome(device, By.descContains("unwatched episodes"))
		var episode: UiObject2? = null
		repeat(5) {
			episode = episode ?: device.wait(Until.findObject(By.textContains(EPISODE)), 4_000) ?: run { ProfileTarget.scroll(device, down = true); null }
		}
		checkNotNull(episode) { "the show page has no episode row" }.swipe(Direction.RIGHT, 0.6f)
		device.waitForIdle()
		checkNotNull(device.findObject(By.textContains(EPISODE))) { "the episode row went away" }.click()
		device.waitForIdle()
		repeat(2) { ProfileTarget.scroll(device, down = true) }
		repeat(2) { ProfileTarget.scroll(device, down = false) }
		device.pressBack()
		device.pressBack()
	}

	private fun journey(block: MacrobenchmarkScope.() -> Unit) = rule.collect(
		packageName = ProfileTarget.packageName,
		maxIterations = 4,
		stableIterations = 2,
	) {
		pressHome()
		startActivityAndWait()
		block()
	}

	private companion object {
		/** The fixture show's second episode, also on Home under Continue Watching. */
		const val EPISODE = "Gran Dillama"
	}
}

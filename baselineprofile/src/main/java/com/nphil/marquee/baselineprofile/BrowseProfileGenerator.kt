package com.nphil.marquee.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records scrolling Home and the Library, where a session spends most of its time: shelves and a grid of posters, with
 * their art arriving as they pass. The build answers from the iPhone app's fixtures, see [ProfileTarget].
 */
@RunWith(AndroidJUnit4::class)
class BrowseProfileGenerator {
	@get:Rule
	val rule = BaselineProfileRule()

	@Test
	fun homeScroll() = journey {
		scrollThrough()
	}

	@Test
	fun libraryScroll() = journey {
		val tab = device.findObject(By.text("Library")) ?: device.findObject(By.desc("Library"))
		checkNotNull(tab) { "there is no Library tab" }.click()
		// The grid's first posters; scrolling a page that is still empty would record none of it.
		device.wait(Until.hasObject(By.descContains(", movie")), 8_000)
		scrollThrough()
	}

	/** The page down to its end and back up, a screenful at a time. */
	private fun MacrobenchmarkScope.scrollThrough() {
		repeat(PAGES) { ProfileTarget.scroll(device, down = true) }
		repeat(PAGES) { ProfileTarget.scroll(device, down = false) }
	}

	private fun journey(block: MacrobenchmarkScope.() -> Unit) = rule.collect(
		packageName = ProfileTarget.packageName,
		maxIterations = 4,
		stableIterations = 2,
	) {
		pressHome()
		startActivityAndWait()
		check(device.wait(Until.hasObject(By.text("Home")), 15_000)) { "Home did not appear" }
		block()
	}

	private companion object {
		const val PAGES = 5
	}
}

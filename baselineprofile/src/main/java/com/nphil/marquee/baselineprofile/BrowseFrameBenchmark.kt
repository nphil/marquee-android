package com.nphil.marquee.baselineprofile

import android.graphics.Point
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures browse frames on the fixture build, not app startup or profile generation. */
@RunWith(AndroidJUnit4::class)
class BrowseFrameBenchmark {
	@get:Rule
	val rule = MacrobenchmarkRule()

	@Test
	fun homeScroll() {
		var headingTop = 0
		measureHome(setup = {
			headingTop = checkNotNull(device.findObject(firstShelf)).visibleBounds.top
		}) {
			ProfileTarget.scroll(device, down = true)
			val newTop = device.findObject(firstShelf)?.visibleBounds?.top
			check(device.hasObject(By.desc("Edit Home")) && (newTop == null || newTop < headingTop)) {
				"The swipe did not scroll Home"
			}
		}
	}

	@Test
	fun openTitle() {
		lateinit var tap: Point
		measureHome(setup = { tap = findTitlePoster() }) {
			check(device.click(tap.x, tap.y)) { "Could not tap the $TITLE poster" }
			// Let the 350 ms shared-element flight finish before querying the accessibility tree.
			SystemClock.sleep(600)
			check(device.wait(Until.hasObject(By.text(TITLE)), LOAD_TIMEOUT_MS) && device.hasObject(By.desc("Back"))) {
				"The $TITLE detail page did not load"
			}
		}
	}

	private fun measureHome(setup: MacrobenchmarkScope.() -> Unit, action: MacrobenchmarkScope.() -> Unit) =
		rule.measureRepeated(
			packageName = ProfileTarget.packageName,
			metrics = listOf(FrameTimingMetric()),
			compilationMode = CompilationMode.Full(),
			// Reset the activity (including the scroll offset), keeping the process and artwork cache warm.
			startupMode = StartupMode.WARM,
			iterations = 10,
			setupBlock = {
				startActivityAndWait()
				check(device.wait(Until.hasObject(firstShelf), LOAD_TIMEOUT_MS)) { "Fixture Home did not load" }
				device.waitForIdle()
				setup()
			},
			measureBlock = action,
		)

	private fun MacrobenchmarkScope.findTitlePoster(): Point {
		// Use a movie poster, not a Continue Watching card whose tap would start playback.
		val poster = By.descStartsWith("$TITLE, movie")
		repeat(4) {
			device.findObject(poster)?.let { return it.visibleCenter }
			ProfileTarget.scroll(device, down = true)
		}
		return checkNotNull(device.findObject(poster)) { "No $TITLE poster on fixture Home" }.visibleCenter
	}

	private companion object {
		val firstShelf = By.text("Continue Watching")
		const val TITLE = "Charge"
		const val LOAD_TIMEOUT_MS = 15_000L
	}
}

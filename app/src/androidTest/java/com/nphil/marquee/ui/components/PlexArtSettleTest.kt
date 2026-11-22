package com.nphil.marquee.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [rememberSettled], with the clock held so the wait can be counted out frame by frame. */
@RunWith(AndroidJUnit4::class)
class PlexArtSettleTest {
	@get:Rule
	val rule = createComposeRule()

	private var wanted by mutableIntStateOf(1)
	private var art by mutableStateOf("poster")

	private fun shows(size: Int) {
		rule.onNodeWithText("size $size").assertExists()
	}

	private fun change(size: Int, art: String = this.art) = rule.runOnIdle {
		this.wanted = size
		this.art = art
	}

	@Test
	fun aSizeThatKeepsChangingIsAskedForOnceAfterItStops() {
		rule.mainClock.autoAdvance = false
		rule.setContent { Text("size ${rememberSettled(wanted, art)}") }
		shows(1)

		change(2)
		rule.mainClock.advanceTimeBy(100)
		change(3)
		// 250 ms after the first change, but only 150 after the last.
		rule.mainClock.advanceTimeBy(150)
		shows(1)

		rule.mainClock.advanceTimeBy(250)
		shows(3)
	}

	@Test
	fun aSizeThatGoesBackToWhatIsShownNeverChangesIt() {
		rule.mainClock.autoAdvance = false
		rule.setContent { Text("size ${rememberSettled(wanted, art)}") }

		change(4)
		rule.mainClock.advanceTimeBy(100)
		change(1)
		rule.mainClock.advanceTimeBy(1_000)
		shows(1)
	}

	@Test
	fun anotherImageTakesItsSizeAtOnce() {
		rule.mainClock.autoAdvance = false
		rule.setContent { Text("size ${rememberSettled(wanted, art)}") }

		change(5, art = "backdrop")
		rule.mainClock.advanceTimeBy(50)
		shows(5)
	}
}

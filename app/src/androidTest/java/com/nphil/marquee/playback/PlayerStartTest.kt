package com.nphil.marquee.playback

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.ui.AppNavigation
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play on a title's page, end to end on the emulator: the real [AppNavigation], Core's play start over the fake Plex of
 * core/UITests/FakePlex, Media3 reading a real clip from the fake over HTTP, and the player's reports. The fake serves
 * the 4 s [Clips.Flat] as Elephants Dream's file. The film plays when the server has been told it is playing; it ends
 * when the server has been told it is watched and the page is back; and the player's last word is a stop.
 *
 * The screen is not what is checked while it plays: the controls hide on their own timer, which the test clock runs
 * ahead of real time. The server's log is the witness.
 */
@RunWith(AndroidJUnit4::class)
class PlayerStartTest {
	@get:Rule(order = 0)
	val app = SignedInToFake(film = Clips.Flat.file)

	@get:Rule(order = 1)
	val animations = SystemAnimations()

	@get:Rule(order = 2)
	val rule = createAndroidComposeRule<ComponentActivity>()

	private val play = hasText("Play") and hasClickAction()

	@Test
	fun playOnATitlesPageStartsTheFilmAndItsEndMarksItWatched() {
		show()
		rule.onNodeWithText("Open Elephants Dream").performClick()
		await("the page's Play button") { rule.onAllNodes(play).fetchSemanticsNodes().isNotEmpty() }
		rule.onAllNodes(play).onFirst().performClick()

		await("a report that the film is playing") { reports().any { "state=playing" in it } }
		await("the film marked watched when it ended") { app.fake.requestsTo("/:/scrobble").any { "key=$ElephantsDreamKey" in it } }
		await("the title's page back after the film ended") { rule.onAllNodes(play).fetchSemanticsNodes().isNotEmpty() }
		await("the stop report") { reports().any { "state=stopped" in it } }
	}

	private fun reports() = app.fake.requestsTo("/:/timeline")

	/** Fails with the screen and the paths the app asked the server for, never their queries: those carry tokens. */
	private fun await(what: String, condition: () -> Boolean) {
		try {
			rule.waitUntil(LoadMillis, condition)
		} catch (e: Throwable) {
			val asked = app.fake.requests.joinToString("\n") { it.substringBefore('?') }
			throw AssertionError("no $what within $LoadMillis ms.\nThe app asked for:\n$asked\nThe screen:\n${rule.onRoot().printToString()}", e)
		}
	}

	/** The app's themed canvas with [AppNavigation] over a stand-in for the tabs, in a tablet's window. */
	private fun show() {
		val container = app.container
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			// The app's own client cannot reach the fake server; the fake's can.
			CompositionLocalProvider(LocalPlaybackHttp provides app.fake.client) {
				DeviceWindow(Device.Tablet) {
					MarqueeTheme(theme, container.typography) {
						Box(Modifier.fillMaxSize().background(lucent.canvas)) {
							AppNavigation(container) { navigator -> Tabs(navigator) }
						}
					}
				}
			}
		}
	}

	@Composable
	private fun Tabs(navigator: Navigator) {
		Button(onClick = { navigator.openItem(ElephantsDreamKey) }) { Text("Open Elephants Dream") }
	}

	private companion object {
		/** Long enough for the emulator's first frames, the fixtures' answers, the decoder's start and a 4 s film. */
		const val LoadMillis = 40_000L
		const val ElephantsDreamKey = "105"
	}
}

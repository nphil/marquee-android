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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.Film
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.ui.AppNavigation
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.detail.HeroTag
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A trailer starting on a title page on the emulator, filmed frame by frame: the real [AppNavigation] and the page of
 * Elephants Dream over the fake Plex of core/UITests/FakePlex, with a stand-in for the tabs. The fake serves the
 * letterboxed [Clips.Scope] as the movie's trailer, so the trailer is the real one end to end: the page's dwell, the
 * player over the fake's HTTP, the decoder, and the picture over the page's art. The clock stops before the dwell runs
 * out and moves a frame at a time, and each frame is a shot of the hero: the art alone, the video's first frame, and the
 * cross-fade to its end. It is saved as a numbered contact sheet among the review screenshots, the frame the picture
 * began to show in red.
 *
 * The art is seen first and nothing is drawn over it until the picture shows: no flash of black or of a shutter. The
 * picture fades in rather than cutting in. When it has, the letterbox is cropped away and the hero is picture to every
 * edge.
 */
@RunWith(AndroidJUnit4::class)
class TrailerStartTest {
	@get:Rule(order = 0)
	val app = SignedInToFake(trailer = Clips.Scope.file)

	@get:Rule(order = 1)
	val animations = SystemAnimations()

	@get:Rule(order = 2)
	val rule = createAndroidComposeRule<ComponentActivity>()

	private val probe = HeroProbe(rule, HeroTag)
	private val film = Film(TileWidth)

	@After
	fun startTheClockAgain() {
		rule.mainClock.autoAdvance = true
	}

	@Test
	fun aLetterboxedTrailerFadesInOverThePagesArtAndFillsItsHero() {
		show()
		rule.onNodeWithText("Open Elephants Dream").performClick()
		awaitPage()
		probe.await("the page's hero", LoadMillis) { rule.onAllNodesWithTag(HeroTag).fetchSemanticsNodes().isNotEmpty() }
		rule.waitForIdle()
		awaitStill()
		// The clock stops before the dwell runs out. Left running, waiting for idle once the trailer was on screen played the
		// whole fade unfilmed on the CI emulator, and the film opened on the finished picture.
		rule.mainClock.autoAdvance = false
		// The film opens on the art as the page shows it before any trailer, so a picture already on its way when the
		// film starts shows as a jump from it instead of going unseen.
		assertTrue("the trailer was on screen before the art was shot: the page's dwell ran out first", probe.state() == null)
		val quiet = Film()
		probe.shoot(film, quiet) {}
		awaitTrailer()

		val shownAt = probe.filmFade(film, quiet, ArtFrames, FadeFrames, FilmMillis)
		val sheet = film.contactSheet(Columns, shownAt)
		sheet.saveScreenshot("trailer-start-page-letterbox")
		sheet.recycle()

		val report = "the picture showed at frame $shownAt of ${film.size}; place per frame: $film"
		assertTrue("the picture never showed. $report", shownAt >= 0)
		assertTrue("the hero moved ${quiet.reach} from the art before the picture showed: something was drawn over it. $report", quiet.reach <= QuietGap)
		assertTrue("the picture cut in, ${film.biggestStep} of the way in one frame. $report", film.biggestStep <= BiggestStep)
		assertTrue("the picture went back ${film.biggestStepBack} of the way in one frame. $report", film.biggestStepBack <= BiggestStepBack)
		assertTrue("the hero is only ${film.leftover} from the art after the fade: the picture did not take its place. $report", film.leftover >= Replaced)
		probe.assertBars("the letterbox on the title page", Clips.Scope.bars, probe.picture().bars)
		probe.shoot { probe.assertFilled("the title page's hero", it, Inset) }
	}

	/** The page has its title and its watched toggle: the details have arrived and the loading placeholders are gone. */
	private fun awaitPage() {
		probe.await("the page's title and its watched toggle", LoadMillis) {
			rule.onAllNodes(hasText("Elephants Dream") and isHeading()).fetchSemanticsNodes().isNotEmpty() &&
				rule.onAllNodes(hasContentDescription("Mark watched") or hasContentDescription("Mark unwatched")).fetchSemanticsNodes().isNotEmpty()
		}
	}

	/** Runs the page's dwell out a frame at a time, up to the first frame the trailer is on screen in. */
	private fun awaitTrailer() {
		val end = System.currentTimeMillis() + LoadMillis
		while (probe.state() == null) {
			if (System.currentTimeMillis() > end) throw AssertionError("the trailer was not on screen $LoadMillis ms after the page was still, with the clock moving a frame at a time")
			rule.mainClock.advanceTimeByFrame()
		}
	}

	/**
	 * Waits until two shots a moment apart match: the art has arrived and nothing is moving. The art loader's counters
	 * never settled on the CI emulator, so the screen itself is the judge. A page that never goes still is filmed as it is.
	 */
	private fun awaitStill() {
		val end = System.currentTimeMillis() + LoadMillis
		do {
			val shots = Film()
			probe.shoot(shots) {}
			Thread.sleep(StillMillis)
			rule.waitForIdle()
			probe.shoot(shots) {}
		} while (shots.reach > StillGap && System.currentTimeMillis() < end)
	}

	/** The app's themed canvas with [AppNavigation] over a stand-in for the tabs, in a tablet's window: the title page's hero is a card there. */
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
		/** Long enough for the emulator's first frames, the fixtures' answers and the page's two-second dwell. */
		const val LoadMillis = 40_000L

		/** The filming itself: the decoder's start, and a shot of every frame of the fade. */
		const val FilmMillis = 40_000L

		/** Two shots this far apart in real time, and this close in the picture, make a page still. */
		const val StillMillis = 300L
		const val StillGap = 0.001f

		const val TileWidth = 240
		const val Columns = 6
		const val ElephantsDreamKey = "105"

		/** The fade takes 400 ms, 25 frames of 16 ms; the rest show it done. */
		const val FadeFrames = 32

		/** Shots of the art alone the film opens with, the one taken before the trailer started included. */
		const val ArtFrames = 4

		/** Pixels in from the hero's edge that are not looked at: the card's own clip is not the video's. */
		const val Inset = 2

		/** How far the hero may move from the art alone before the picture starts to show. */
		const val QuietGap = 0.03f

		/** How far from the art the hero must be once the picture has faded in: the picture has taken its place. It was 0.38 to 0.40 on the CI emulator. */
		const val Replaced = 0.15f

		// Where the fade sits between the art and the picture, frame to frame. A 400 ms ease moves at most about 0.11 of the
		// way in a frame of 16 ms (0.08 on the CI emulator); a cut moves all of it in one. The video plays on under the fade,
		// so a frame may also step back by what the picture moved.
		const val BiggestStep = 0.2f
		const val BiggestStepBack = 0.1f
	}
}

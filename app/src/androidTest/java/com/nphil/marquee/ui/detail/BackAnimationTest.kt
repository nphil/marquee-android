package com.nphil.marquee.ui.detail

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.testing.Film
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.ui.AppNavigation
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.posterSharedElement
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page closing on the emulator, filmed frame by frame: the real [AppNavigation] and title pages over the fake Plex
 * of core/UITests/FakePlex, with a stand-in for the tabs. The clock stands still between frames, and [Film] places
 * each frame between the page and the screen it reveals. One close rises evenly from the one to the other. A close
 * that plays twice leaves the page standing, shrunken, until the shared poster's flight ends and then cuts it away
 * in one frame: a flat stretch and then a step of a third of the way or more. Each film is saved as a numbered
 * contact sheet among the review screenshots, the frame the finger came off in red.
 *
 * The swipes go through the activity's back dispatcher, as the system's gesture does, so the platform's own gesture
 * is not part of what is tested. Only the app's own stack is filmed: Settings and the Seerr pages close in other
 * containers.
 */
@RunWith(AndroidJUnit4::class)
class BackAnimationTest {
	@get:Rule(order = 0)
	val app = SignedInToFake()

	@get:Rule(order = 1)
	val rule = createAndroidComposeRule<ComponentActivity>()

	private val film = Film()

	/** The last frame shot before the finger came off. */
	private var releasedAt = 0

	private val sintel = Page("Sintel", heading("Sintel")) { awaitMovie("Sintel") }
	private val coffeeRun = Page("Coffee Run", heading("Coffee Run")) { awaitMovie("Coffee Run") }
	private val season = Page("Season 1", heading("Episodes")) {
		waitFor(heading("Season 1"))
		scrollTo(heading("Episodes"))
	}

	@After
	fun startTheClockAgain() {
		rule.mainClock.autoAdvance = true
	}

	@Test
	fun aBackSwipeClosesAMoviePageOnceAndFlowsIntoItsPoster() {
		show()
		rule.onNodeWithTag(PosterTag).performClick()
		startFilming(sintel)
		swipe(far = 0.45f)
		letGo { dispatch { onBackPressed() } }
		assertSmoothClose("movie-swipe", sintel)
	}

	@Test
	fun aQuickBackSwipeClosesAPageThatHasNoPosterOnce() {
		show()
		rule.onNodeWithText("Open Coffee Run").performClick()
		startFilming(coffeeRun)
		swipe(far = 0.3f, dragFrames = 8, heldFrames = 0)
		letGo { dispatch { onBackPressed() } }
		assertSmoothClose("movie-quick-swipe", coffeeRun)
	}

	@Test
	fun aBackSwipeClosesASeasonPageOnce() {
		show()
		rule.onNodeWithText("Open Season 1").performClick()
		startFilming(season)
		swipe(far = 0.45f)
		letGo { dispatch { onBackPressed() } }
		assertSmoothClose("season-swipe", season)
	}

	@Test
	fun theBackArrowClosesAMoviePageOnce() {
		show()
		rule.onNodeWithTag(PosterTag).performClick()
		startFilming(sintel)
		letGo { rule.onAllNodesWithContentDescription("Back").onLast().performClick() }
		assertSmoothClose("movie-arrow", sintel)
	}

	@Test
	fun aBackSwipeCalledOffReturnsToTheMoviePage() {
		show()
		rule.onNodeWithTag(PosterTag).performClick()
		startFilming(sintel)
		swipe(far = 0.45f)
		letGo { dispatch { dispatchOnBackCancelled() } }
		saveFilm("movie-called-off")

		val report = "reach ${film.reach}, leftover ${film.leftover}, finger off after frame $releasedAt of ${film.size}"
		assertTrue("the page did not follow the finger: $report", film.reach >= LeastReach)
		assertTrue("the page did not come back to where it was: $report", film.leftover <= film.reach / 10)
		assertTrue("a swipe called off closed the page", rule.onAllNodes(sintel.marker).fetchSemanticsNodes().isNotEmpty())
	}

	private fun assertSmoothClose(name: String, page: Page) {
		saveFilm(name)
		val step = film.biggestStep
		val stepBack = film.biggestStepBack
		val gain = film.slowestGain(releasedAt)
		val report = "finger off after frame $releasedAt, place per frame: $film"
		assertTrue("${page.title} is still on screen at the end of the close. $report", rule.onAllNodes(page.marker).fetchSemanticsNodes().isEmpty())
		assertTrue("the close jumped $step of the way in one frame. $report", step <= BiggestStep)
		assertTrue("the close went back $stepBack of the way in one frame. $report", stepBack <= BiggestStepBack)
		assertTrue("the close gained only $gain in eight frames while it was under way. $report", gain >= LeastGain)
	}

	/** Takes the finger off: what the page does from here is the close. */
	private fun letGo(action: () -> Unit) {
		releasedAt = film.size - 1
		action()
		frames(AfterFrames)
	}

	/** Starts a back swipe from the start edge and drags it [far] of the way across, a frame per step. */
	private fun swipe(far: Float, dragFrames: Int = 16, heldFrames: Int = 6) {
		dispatch { dispatchOnBackStarted(backEvent(0f)) }
		frames(1)
		for (step in 1..dragFrames) {
			dispatch { dispatchOnBackProgressed(backEvent(far * step / dragFrames)) }
			frames(1)
		}
		frames(heldFrames)
	}

	private fun backEvent(progress: Float) =
		BackEventCompat(touchX = 2f + 300f * progress, touchY = 125f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

	private fun dispatch(action: OnBackPressedDispatcher.() -> Unit) {
		rule.runOnUiThread { rule.activity.onBackPressedDispatcher.action() }
	}

	private fun frames(count: Int) {
		repeat(count) {
			rule.mainClock.advanceTimeByFrame()
			shoot()
		}
	}

	private fun shoot(into: Film = film) {
		val frame = rule.onRoot().captureToImage().asAndroidBitmap()
		into.add(frame)
		frame.recycle()
	}

	/**
	 * Waits until two shots a moment apart match: the pictures have arrived and nothing is moving. The art loader's
	 * counters never settled on these pages on the CI emulator, so the screen itself is the judge. A page that never goes
	 * still is filmed as it is.
	 */
	private fun awaitStill() {
		val end = System.currentTimeMillis() + LoadMillis
		do {
			val shots = Film()
			shoot(shots)
			Thread.sleep(StillMillis)
			rule.waitForIdle()
			shoot(shots)
		} while (shots.reach > StillGap && System.currentTimeMillis() < end)
	}

	/** Waits for the page to open and go still, then stops the clock and shoots the first frame. */
	private fun startFilming(page: Page) {
		page.awaitLoaded()
		awaitStill()
		rule.waitForIdle()
		rule.mainClock.autoAdvance = false
		shoot()
	}

	private fun saveFilm(name: String) {
		val sheet = film.contactSheet(marked = releasedAt)
		sheet.saveScreenshot("back-$name")
		sheet.recycle()
	}

	/** The app's themed canvas with [AppNavigation] over a stand-in for the tabs. */
	private fun show() {
		val container = app.container
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			MarqueeTheme(theme, container.typography) {
				Box(Modifier.fillMaxSize().background(lucent.canvas)) {
					AppNavigation(container) { navigator -> Tabs(navigator) }
				}
			}
		}
	}

	/** A poster that joins the zoom to its page, and a button for each page that has no poster to zoom from. */
	@Composable
	private fun Tabs(navigator: Navigator) {
		Column {
			Box(
				Modifier
					.testTag(PosterTag)
					.size(120.dp, 180.dp)
					.posterSharedElement(SintelKey)
					.clip(RoundedCornerShape(Radius.tile))
					.background(lucent.accent)
					.clickable { navigator.openItem(SintelKey) },
			)
			Button(onClick = { navigator.openItem(CoffeeRunKey) }) { Text("Open Coffee Run") }
			Button(onClick = { navigator.openItem(SeasonKey) }) { Text("Open Season 1") }
		}
	}

	/** A movie's heading and its watched toggle, in whichever state the process has left it. */
	private fun awaitMovie(title: String) {
		waitFor(heading(title))
		waitFor(hasContentDescription("Mark watched") or hasContentDescription("Mark unwatched"))
	}

	private fun waitFor(matcher: SemanticsMatcher) {
		rule.waitUntil(LoadMillis) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
	}

	/** Scrolls the page until [matcher]'s row is composed: the page is a lazy list, and its rows arrive as they load. */
	private fun scrollTo(matcher: SemanticsMatcher) {
		rule.waitUntil(LoadMillis) {
			runCatching { rule.onNode(ScrollsVertically).performScrollToNode(matcher) }.isSuccess
		}
	}

	private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

	private fun heading(text: String) = hasText(text) and isHeading()

	/** A page under test: [awaitLoaded] waits until it shows what it loads, and its [marker] is gone once it has closed. */
	private inner class Page(val title: String, val marker: SemanticsMatcher, val awaitLoaded: () -> Unit)

	private companion object {
		/** Long enough for the emulator's first frames and the fixtures' answers. */
		const val LoadMillis = 20_000L

		/** Two shots this far apart in real time, and this close in the picture, make a page still. */
		const val StillMillis = 300L
		const val StillGap = 0.001f

		/** Frames of 16 ms after the finger comes off: the close takes 350 ms and ends well inside this. */
		const val AfterFrames = 40

		const val PosterTag = "stand-in poster"
		const val SintelKey = "108"
		const val CoffeeRunKey = "103"
		const val SeasonKey = "202"

		// Where a close sits between the page and the screen it reveals, on the screens' replica with the fix and without it.
		// Fixed: at most 0.09 of the way in a frame, never back, and 0.14 or more in eight frames once the finger is off.
		// Before: 0.32 to 0.49 in one frame (the page cut away), and 0.02 or less in eight frames (the page left standing).
		const val BiggestStep = 0.2f
		const val BiggestStepBack = 0.1f
		const val LeastGain = 0.07f

		/** A swipe called off moved the page by 0.16 on the replica. */
		const val LeastReach = 0.02f

		/** The page's list, not the shelves' rows. */
		val ScrollsVertically = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
	}
}

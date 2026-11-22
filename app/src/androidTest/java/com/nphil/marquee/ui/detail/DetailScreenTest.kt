package com.nphil.marquee.ui.detail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.container
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.fakeplex.FakePlexServer
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.session.SavedPlexSignIn
import com.nphil.marquee.ui.AppNavigation
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The title pages on the emulator, over the fake Plex of core/UITests/FakePlex: the real app container, signed in
 * to the fake server the way a returning person is, and the real Core behind it. Each test opens a page from a
 * stand-in tab and checks what the page shows and what a tap on it sends to the server. Each uses its own titles,
 * because what the app marks changed is kept for the whole process.
 */
@RunWith(AndroidJUnit4::class)
class DetailScreenTest {
	@get:Rule
	val rule = createComposeRule()

	private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var fake: FakePlexServer

	@Before
	fun signInToTheFake() {
		fake = FakePlexServer.start(InstrumentationRegistry.getInstrumentation().context)
		fake.install()
		val container = context.container
		runBlocking { container.core.reset() }
		SavedPlexSignIn(fake.token, listOf(fake.server), fake.profile).save(SecretStore(context))
		runBlocking { container.session.restore() }
		assertEquals(AppSession.Phase.Ready, container.session.state.value.phase)
	}

	@After
	fun signOut() {
		runBlocking { context.container.session.signOut().join() }
		fake.stop()
	}

	@Test
	fun aMoviePageShowsItsTitleSummaryAndActionsAndBackClosesIt() {
		show()
		open("Sintel")
		waitFor(hasText("Sintel") and isHeading())
		scrollTo(hasText("A young woman crosses wild lands", substring = true))
		rule.onNodeWithContentDescription("Mark watched").assertHasClickAction()
		rule.onNodeWithContentDescription("More").assertHasClickAction()

		rule.onAllNodesWithContentDescription("Back").onLast().performClick()
		waitFor(hasText("Open Sintel"))
		waitUntilGone(hasText("Sintel"))
	}

	@Test
	fun markingAMovieWatchedTellsTheServerAndFlipsTheButton() {
		show()
		open("Charge")
		waitFor(hasContentDescription("Mark watched"))

		rule.onNodeWithContentDescription("Mark watched").performClick()
		rule.waitUntil(LoadMillis) { fake.requestsTo("/:/scrobble").isNotEmpty() }
		assertTrue(fake.requestsTo("/:/scrobble").single().contains("key=102"))
		waitFor(hasContentDescription("Mark unwatched"))
	}

	@Test
	fun aShowPageListsItsEpisodesAndAnEpisodeOpensItsOwnPage() {
		show()
		open("Caminandes")
		waitFor(hasText("Caminandes") and isHeading())
		scrollTo(hasClickLabel("Choose a season"))
		for (episode in Episodes) scrollTo(hasClickLabel("Open $episode"))
		scrollTo(hasClickLabel("Open ${Episodes.first()}"))
		// An episode already watched on the server shows as watched, and one that is not offers the mark.
		rule.onNodeWithContentDescription("Mark Llama Drama unwatched").assertHasClickAction()
		rule.onNodeWithContentDescription("Mark Gran Dillama watched").assertHasClickAction()

		rule.onNode(hasClickLabel("Open Llama Drama")).performClick()
		waitFor(hasText("Llama Drama") and isHeading())
		// An episode's page links up to its show.
		waitFor(hasClickLabel("Open Caminandes"))

		rule.onAllNodesWithContentDescription("Back").onLast().performClick()
		waitUntilGone(hasClickLabel("Open Caminandes"))
		scrollTo(hasClickLabel("Open Llama Drama"))
	}

	@Test
	fun swipingAnEpisodeFromTheStartEdgeMarksItWatched() {
		show()
		open("Caminandes")
		scrollTo(hasClickLabel("Open Gran Dillama"))
		waitFor(hasContentDescription("Mark Gran Dillama watched"))

		rule.onNode(hasClickLabel("Open Gran Dillama")).performTouchInput { swipeRight() }
		waitFor(hasContentDescription("Mark Gran Dillama unwatched"))
	}

	@Test
	fun aSeasonPageListsItsEpisodesUnderAHeading() {
		show()
		open("Season 1")
		waitFor(hasText("Season 1") and isHeading())
		scrollTo(hasText("Episodes") and isHeading())
		for (episode in Episodes) scrollTo(hasClickLabel("Open $episode"))
	}

	@Test
	fun theWidePageHasTheSameTitleSummaryAndActions() {
		show(wide = true)
		open("Coffee Run")
		waitFor(hasText("Coffee Run") and isHeading())
		waitFor(hasContentDescription("Mark watched"))
		waitFor(hasContentDescription("More"))
	}

	/** The app's themed canvas with [AppNavigation] over a stand-in for the tabs: one button per title the tests open. */
	private fun show(wide: Boolean = false) {
		val container = context.container
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			MarqueeTheme(theme, container.typography) {
				// A window wider than the emulator's reaches the tablet layout; the page is laid out at 1100 dp whatever is on screen.
				val size = if (wide) Modifier.requiredSize(1100.dp, 700.dp) else Modifier.fillMaxSize()
				Box(size.background(lucent.canvas)) {
					AppNavigation(container) { navigator -> Tabs(navigator) }
				}
			}
		}
	}

	@Composable
	private fun Tabs(navigator: Navigator) {
		Column {
			for ((title, key) in Titles) Button(onClick = { navigator.openItem(key) }) { Text("Open $title") }
		}
	}

	private fun open(title: String) {
		rule.onNodeWithText("Open $title").performClick()
	}

	private fun waitFor(matcher: SemanticsMatcher) {
		rule.waitUntil(LoadMillis) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
	}

	private fun waitUntilGone(matcher: SemanticsMatcher) {
		rule.waitUntil(LoadMillis) { rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
	}

	/** Scrolls the page until [matcher]'s row is composed: the page is a lazy list, and its rows arrive as they load. */
	private fun scrollTo(matcher: SemanticsMatcher) {
		rule.waitUntil(LoadMillis) {
			runCatching { rule.onNode(ScrollsVertically).performScrollToNode(matcher) }.isSuccess
		}
	}

	private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

	private fun hasClickLabel(label: String) = SemanticsMatcher("clicks to \"$label\"") {
		it.config.getOrNull(SemanticsActions.OnClick)?.label == label
	}

	private companion object {
		/** Long enough for the emulator's first frames and the fixtures' answers. */
		const val LoadMillis = 20_000L

		val Titles = listOf("Sintel" to "108", "Charge" to "102", "Coffee Run" to "103", "Caminandes" to "201", "Season 1" to "202")
		val Episodes = listOf("Llama Drama", "Gran Dillama", "Llamigos")

		/** The page's list, not the shelves' rows. */
		val ScrollsVertically = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
	}
}

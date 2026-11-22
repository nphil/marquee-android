package com.nphil.marquee.ui.review

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.testing.saveWindow
import com.nphil.marquee.ui.MarqueeApp
import com.nphil.marquee.ui.library.LibraryRailTag
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The review screenshots of the real screens in a phone window and a tablet window: the app's own root over the fake
 * Plex of core/UITests/FakePlex, driven the way a person drives it. Each case asserts what its screen shows before it
 * saves the picture, so a blank render fails instead of being uploaded. The screenshots are named
 * `<screen>-<device>[-state].png`; see [Screen].
 */
@RunWith(Parameterized::class)
class ReviewScreenshotsTest(private val screen: Screen, private val device: Device) {
	@get:Rule(order = 0)
	val app = SignedInToFake()

	@get:Rule(order = 1)
	val rule = createComposeRule()

	@Test
	fun capture() {
		// What a returning person has set, so every case starts from the same place whatever ran before it.
		app.prefer { profile ->
			setLibraryChoice(profile, MoviesLibrary)
			setRecentSearches(profile, RecentSearches)
			setSidebarExpanded(true)
		}
		val container = app.container
		val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
		rule.setContent {
			DeviceWindow(device) {
				MarqueeTheme(theme, container.typography) { MarqueeApp(container, Dark, onTheme = {}) }
			}
		}

		when (screen) {
			Screen.Home -> home()
			Screen.Library -> library()
			Screen.LibraryHub -> libraryHub()
			Screen.Watchlist -> watchlist()
			Screen.SearchRecents -> searchRecents()
			Screen.SearchResults -> searchResults()
			Screen.TabBar -> settingsPage("Tab Bar", hasText("On Scroll"))
			Screen.FavoriteLibraries -> settingsPage("Favorite Libraries", hasText("Which libraries this device shows."))
		}
		if (screen.hasArt) app.art.awaitSettled(rule)
		rule.saveWindow(screen.fileName(device))
	}

	private fun home() {
		await(hasText("Continue Watching"))
		await(hasContentDescription("Sintel", substring = true))
	}

	private fun library() {
		openTab("Library")
		// The first title in title order, which the grid always composes, and the rail that title order brings.
		await(hasContentDescription("Big Buck Bunny", substring = true))
		await(hasTestTag(LibraryRailTag))
	}

	private fun libraryHub() {
		openTab("Library")
		click(hasText("Recommended") and hasClickAction())
		click(hasClickLabel("See all Top Rated"))
		rule.waitUntilDoesNotExist(hasClickLabel("See all Top Rated"), LoadMillis)
		await(hasText("Top Rated"))
		await(hasContentDescription("Big Buck Bunny", substring = true))
	}

	private fun watchlist() {
		openTab("Watchlist")
		// A title that is on the Watchlist and nowhere in the library.
		await(hasContentDescription("Wing It!", substring = true))
	}

	private fun searchRecents() {
		openTab("Search")
		await(hasText("Recent Searches"))
		await(hasClickLabel("Search for ${RecentSearches.first()}"))
	}

	// A recent search is typed for the person, so no keyboard comes up over the page. A phone lists the results as rows
	// that open by their title; a tablet lays them out as poster and episode cards that fill the width.
	private fun searchResults() {
		openTab("Search")
		click(hasClickLabel("Search for Caminandes"))
		for (title in listOf("Caminandes", "Llama Drama", "Gran Dillama", "Llamigos")) await(searchResult(title))
		if (device != Device.Phone) {
			// The third episode card sits in the right half of the window, so the results are not a column on the left.
			val card = rule.onNode(searchResult("Llamigos")).fetchSemanticsNode().boundsInRoot
			val width = rule.onRoot().fetchSemanticsNode().size.width
			assertTrue("Llamigos at ${card.left} of $width px", card.left > width / 2f)
		}
	}

	private fun searchResult(title: String) =
		if (device == Device.Phone) hasClickLabel("Open $title") else hasContentDescription(title, substring = true) and hasClickAction()

	/** Settings as the Home bar's button (phone) or the sidebar's footer (tablet) opens it, then the page [row] opens. */
	private fun settingsPage(row: String, anchor: SemanticsMatcher) {
		click(hasContentDescription("Settings") and hasClickAction())
		click(hasText(row) and hasClickAction())
		await(anchor)
	}

	/** The bottom bar's item on a phone, the sidebar's on a tablet: both are tabs, named by their text or description. */
	private fun openTab(label: String) {
		click(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and (hasText(label) or hasContentDescription(label)))
	}

	private fun await(matcher: SemanticsMatcher) = rule.waitUntilAtLeastOneExists(matcher, LoadMillis)

	private fun click(matcher: SemanticsMatcher) {
		await(matcher)
		rule.onAllNodes(matcher).onFirst().performClick()
	}

	private fun hasClickLabel(label: String) = SemanticsMatcher("clicks to \"$label\"") {
		it.config.getOrNull(SemanticsActions.OnClick)?.label == label
	}

	/**
	 * What is shot and in which windows. [file] and [state] name it: Settings' two pages are the `settings` screen in
	 * the `tab-bar` and `favorite-libraries` states. [hasArt] screens wait for their posters to arrive first.
	 */
	enum class Screen(
		val file: String,
		val state: String? = null,
		val devices: List<Device> = Handhelds,
		val hasArt: Boolean = true,
	) {
		Home("home", devices = Device.entries),
		Library("library", devices = Device.entries),
		LibraryHub("library", "hub"),
		Watchlist("watchlist"),
		SearchRecents("search", "recents", hasArt = false),
		SearchResults("search", "results"),
		TabBar("settings", "tab-bar", hasArt = false),
		FavoriteLibraries("settings", "favorite-libraries", hasArt = false),
		;

		fun fileName(device: Device) = listOfNotNull(file, device.file, state).joinToString("-")
	}

	companion object {
		/** Long enough for the emulator's first frames and the fixtures' answers. */
		private const val LoadMillis = 20_000L

		/** The fixture's Movies library, which Library opens on. */
		private const val MoviesLibrary = "1"

		private val RecentSearches = listOf("Sintel", "Caminandes", "Elephants Dream")

		/** The theme and appearance pinned, so the screenshots do not follow the emulator's night mode. */
		private val Dark = ThemeChoice(matchSystem = false, fixedMode = ThemeMode.Dark)

		@JvmStatic
		@Parameterized.Parameters(name = "{0}-{1}")
		fun cases(): List<Array<Any>> = Screen.entries.flatMap { screen -> screen.devices.map { arrayOf<Any>(screen, it) } }
	}
}

private val Handhelds = listOf(Device.Phone, Device.Tablet)

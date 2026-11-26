package com.nphil.marquee.ui.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.core.liveGuide
import com.nphil.marquee.data.LiveLists
import com.nphil.marquee.playback.LocalPlaybackHttp
import com.nphil.marquee.testing.Device
import com.nphil.marquee.testing.DeviceWindow
import com.nphil.marquee.testing.SignedInToFake
import com.nphil.marquee.testing.saveScreenshot
import com.nphil.marquee.testing.saveWindow
import com.nphil.marquee.ui.AppNavigation
import com.nphil.marquee.ui.components.ProvidePlexImages
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.MarqueeTheme
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveGuideFixtureTest {
	@get:Rule(order = 0)
	val app = SignedInToFake()

	@get:Rule(order = 1)
	val rule = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun editingARecordingShowsTheNewEpisodesSummary() {
		val container = app.container
		val model = runBlocking { withTimeout(10_000) { container.live.guide.filterNotNull().first().dvr } }
		var mode by mutableStateOf(ThemeMode.Dark)
		val display = InstrumentationRegistry.getInstrumentation().uiAutomation
		rule.setContent {
			val palette = if (mode == ThemeMode.Dark) LucentTheme.DEFAULT_DARK else LucentTheme.DEFAULT_LIGHT
			MarqueeTheme(container.themes.resolve(palette, mode), container.typography) {
				RecordingBrowserScreen(model, Modifier.fillMaxSize()) {}
			}
		}
		rule.waitUntil(10_000) { rule.onAllNodesWithText("News 7 Tonight").fetchSemanticsNodes().isNotEmpty() }
		rule.waitForIdle()
		display.takeScreenshot().saveScreenshot("recording-upcoming-dark")
		rule.runOnIdle { mode = ThemeMode.Light }
		rule.waitForIdle()
		display.takeScreenshot().saveScreenshot("recording-upcoming-light")
		rule.onNodeWithText("Rules").performClick()
		rule.waitUntil(10_000) { rule.onAllNodesWithText("Wild Coasts").fetchSemanticsNodes().isNotEmpty() }
		rule.waitForIdle()
		display.takeScreenshot().saveScreenshot("recording-rules-light")
		rule.onNodeWithText("Wild Coasts").performClick()
		rule.onNodeWithText("Edit rule").performClick()
		rule.waitUntil(10_000) { rule.onAllNodesWithText("New Episodes").fetchSemanticsNodes().isNotEmpty() }
		rule.onNodeWithText("New Episodes").performClick()
		rule.waitUntil(5_000) { rule.onAllNodesWithText("Records new episodes of Wild Coasts", substring = true).fetchSemanticsNodes().isNotEmpty() }
		rule.waitForIdle()
		display.takeScreenshot().saveScreenshot("recording-editor-new-episodes-light")
	}

	@Test
	fun narrowGuideShowsNowAndNextAndKeepsItsSelectionAfterWidening() {
		val fixture = schedule()
		var width by mutableStateOf(580.dp)
		var mode by mutableStateOf(ThemeMode.Light)
		var fontScale by mutableStateOf(1f)
		var selected by mutableStateOf<String?>(null)
		val cursor = mutableDoubleStateOf(Now)
		val container = app.container
		rule.setContent {
			DeviceWindow(Device.Tablet) {
				val theme = container.themes.resolve(if (mode == ThemeMode.Light) LucentTheme.DEFAULT_LIGHT else LucentTheme.DEFAULT_DARK, mode)
				MarqueeTheme(theme, container.typography) {
					ProvidePlexImages(container) {
						Box(Modifier.fillMaxSize().background(lucent.canvas)) {
							CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
								val rows = rememberLazyListState()
								Box(Modifier.width(width).fillMaxHeight()) {
									GuideSchedule(
										fixture, fixture.channels, LiveLists(), Now, cursor, rows,
										favourite = {}, watch = { selected = "watch:${it.id}" }, mark = { _, _ -> null },
									) { channel, programme -> selected = "${channel.id}:${programme.title}" }
								}
							}
						}
					}
				}
			}
		}
		rule.onNodeWithTag("live-guide-list").assertIsDisplayed()
		rule.onNodeWithText("The Lighthouse Keepers").assertIsDisplayed()
		rule.onNodeWithText("City Desk").performClick()
		rule.runOnIdle { assertEquals("ch-0201:City Desk", selected) }
		rule.onNodeWithTag("live-guide-list").saveScreenshot("live-guide-split-light")

		rule.runOnIdle { width = 1000.dp; mode = ThemeMode.Dark }
		rule.onNodeWithTag("live-guide-grid").assertIsDisplayed()
		rule.onNodeWithText("2.1 WMRQ").assertIsDisplayed()
		rule.onNodeWithText("City Desk").assertIsDisplayed()
		rule.runOnIdle { assertEquals(Now, cursor.doubleValue, 0.0) }
		rule.onNodeWithText("Jump to Now").performClick()
		rule.runOnIdle { assertTrue(cursor.doubleValue.isNaN()) }
		rule.onNodeWithTag("live-guide-grid").saveScreenshot("live-guide-tablet-dark")

		rule.runOnIdle { fontScale = 1.5f; width = 411.dp; mode = ThemeMode.Light }
		rule.onNodeWithTag("live-guide-list").assertIsDisplayed()
		rule.onNodeWithText("2.1 WMRQ").performClick()
		rule.runOnIdle { assertEquals("watch:ch-0201", selected) }
		rule.onNodeWithTag("live-guide-list").saveScreenshot("live-guide-phone-large-type-light")
	}

	@Test
	fun quickGuideShowsRecentChannelsAndSelectsAChannelWithoutLeavingThePlayer() {
		val fixture = schedule()
		val container = app.container
		var picked: String? = null
		rule.setContent {
			DeviceWindow(Device.Phone) {
				MarqueeTheme(container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark), container.typography) {
					Box(Modifier.fillMaxSize().background(lucent.canvas)) {
						LiveQuickGuide(
							fixture, LiveLists(recents = listOf("ch-0201", "ch-0701")), "ch-0201", Now,
							Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.dp),
							dismiss = {}, favourite = {}, watch = { picked = it.id }, open = { _, _ -> },
						)
					}
				}
			}
		}
		rule.onNodeWithText("Recent").assertIsDisplayed()
		rule.onNodeWithText("Now · The Lighthouse Keepers").assertIsDisplayed()
		rule.saveWindow("live-quick-guide-phone-dark")
		rule.onAllNodesWithText("7.1 WNWS")[0].performClick()
		rule.runOnIdle { assertEquals("ch-0701", picked) }
	}

	@Test
	fun choosingAChannelHandsItsPlexPlaylistToMedia3AndClosingReleasesTheGrab() {
		val container = app.container
		rule.setContent {
			DeviceWindow(Device.Phone) {
				val theme = container.themes.resolve(LucentTheme.DEFAULT_DARK, ThemeMode.Dark)
				MarqueeTheme(theme, container.typography) {
					CompositionLocalProvider(LocalPlaybackHttp provides app.fake.client) {
						AppNavigation(container) {
							val place by container.browse.source.place.collectAsStateWithLifecycle()
							place?.let { LiveGuideDestination(container, it, onBack = null) }
						}
					}
				}
			}
		}
		rule.waitUntil(15_000) { rule.onAllNodesWithText("2.1 WMRQ").fetchSemanticsNodes().isNotEmpty() }
		rule.onNodeWithText("2.1 WMRQ").performClick()
		// The shared fixture has a null-packet playlist. The request proves the handoff, not video decoding.
		rule.waitUntil(15_000) { app.fake.requestsTo("/video/:/transcode/universal/start.m3u8").isNotEmpty() }
		rule.onNodeWithContentDescription("Close player").performClick()
		rule.waitUntil(10_000) { app.fake.requestsTo("/media/grabbers/operations/ch-0201-uitest", method = "DELETE").isNotEmpty() }
		check(app.fake.requestsTo("/:/timeline").any { "state=stopped" in it })
	}

	private fun schedule(): GuideWindow = runBlocking {
		val container = app.container
		val place = withTimeout(10_000) { container.browse.source.place.filterNotNull().first() }
		val directory = app.context.cacheDir.resolve("live-fixture-guide").path
		checkNotNull(container.core.liveGuide(place.server, null, directory, Now))
	}

	private companion object { const val Now = 1_800_002_460.0 }
}

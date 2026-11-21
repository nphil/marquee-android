package com.nphil.marquee.diagnostics

import com.nphil.marquee.core.PlexConnection
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagReportTextTest {
	private val facts = DiagReportText.Facts(
		phone = "Pixel Tablet · Android 16 · tablet",
		power = "battery 80% · not charging",
		network = "wifi",
		memory = "Java 40 MB",
		server = "local · http · version 1.41.0",
		theme = "dark slate only",
		diagnostics = "live on · Standard · floating button off",
		accessibility = "font 1.0",
	)

	private val local = PlexConnection("http://192.168.1.10:32400", isLocal = true)
	private val relay = PlexConnection("https://relay.example:443", isLocal = false, isRelay = true)
	private val remote = PlexConnection("https://home.example:32400", isLocal = false)

	private fun plex(vararg connections: PlexConnection, version: String? = "1.41.0") = PlexServer(
		name = "Home Server",
		clientIdentifier = "abc",
		isOwned = true,
		isHome = true,
		isPresent = true,
		httpsRequired = false,
		productVersion = version,
		connections = connections.toList(),
	)

	@Test
	fun theDetailsFollowTheSnapshotLayout() {
		val text = DiagReportText.details(facts, "  it froze\nafter login ", "tab=Library path=root sheets=-")
		assertEquals(
			listOf(
				"Phone: Pixel Tablet · Android 16 · tablet",
				"Power: battery 80% · not charging",
				"Network: wifi",
				"Memory: Java 40 MB",
				"Note: it froze",
				"  after login",
				"",
				"Screen: tab=Library path=root sheets=-",
				"Server: local · http · version 1.41.0",
				"Diagnostic Logs: live on · Standard · floating button off",
				"",
				"==== Settings ====",
				"Theme: dark slate only",
				"Accessibility: font 1.0",
			),
			text.lines(),
		)
		assertEquals("Note: -", DiagReportText.details(facts, "  ", "s").lines()[4])
	}

	@Test
	fun theServerIsNamedByRouteSchemeAndVersionOnly() {
		assertEquals("-", DiagReportText.server(null, null))
		assertEquals("local · http · version 1.41.0", DiagReportText.server(plex(local, relay), local.uri))
		assertEquals("relay · https · version 1.41.0", DiagReportText.server(plex(local, relay), relay.uri))
		assertEquals("remote · https · version 1.41.0", DiagReportText.server(plex(remote), remote.uri))
		assertEquals("route unknown · - · version unknown", DiagReportText.server(plex(local, version = null), null))
		val text = DiagReportText.server(plex(local), local.uri)
		assertFalse(text.contains("Home Server"))
		assertFalse(text.contains("192.168"))
	}

	@Test
	fun theThemeAndTheSettingsReadAsWords() {
		val following = ThemeChoice(matchSystem = true, lightId = "paper", darkId = "slate", fixedMode = ThemeMode.Dark)
		assertEquals("matches the system · light paper · dark slate", DiagReportText.theme(following))
		assertEquals("light paper only", DiagReportText.theme(following.copy(matchSystem = false, fixedMode = ThemeMode.Light)))
		assertEquals("match=on light=paper dark=slate fixed=dark", DiagReportText.themeSlots(following))
		assertEquals(
			"live on · Detailed · floating button off",
			DiagReportText.diagnostics(DiagConfig(live = true, detailed = true, button = false)),
		)
	}
}

package com.nphil.marquee.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupScopeTest {
	private fun place(store: String, key: String) = BackupScope.place(store, key)

	@Test
	fun whatIdentifiesThisInstallOrIsPlexsStaysOut() {
		for (key in listOf("plex.clientIdentifier", "plex.addresses", "plex.profile", "shell.sidebarWidth", "search.recents.p1", "marquee.seerr.verified")) {
			assertNull(key, place(BackupScope.SETTINGS, key))
		}
		assertNull(place(BackupScope.DIAGNOSTICS, "name"))
		assertNull(place(BackupScope.REMOTE, "phoneName"))
		// The Plex sign-in, Seerr's session and the linked phones' vault are sealed stores that are not listed.
		assertNull(place(BackupScope.secretStore("secrets"), "session"))
		assertNull(place(BackupScope.secretStore("seerr"), "marquee.seerr.session"))
		assertNull(place(BackupScope.secretStore("link"), "vault"))
	}

	@Test
	fun settingsAreListedUnderTheirKind() {
		assertEquals(BackupGroup.APPEARANCE, place(BackupScope.SETTINGS, "theme.light")?.group)
		assertEquals(BackupGroup.TABS, place(BackupScope.SETTINGS, "shell.tabOrder")?.group)
		assertEquals(BackupGroup.SERVERS, place(BackupScope.SETTINGS, "plex.serverId")?.group)
		assertEquals(BackupGroup.PROFILES, place(BackupScope.SETTINGS, "playback.u1.quality")?.group)
		assertEquals(BackupGroup.TRACKS, place(BackupScope.SETTINGS, "tracks.s.u.title")?.group)
		assertEquals(BackupGroup.LIVE, place(BackupScope.SETTINGS, "live.channels.s")?.group)
		assertEquals(BackupGroup.BIG_SCREEN, place(BackupScope.SETTINGS, "marquee.bigscreen.tilt")?.group)
		assertEquals(BackupGroup.REMOTE, place(BackupScope.REMOTE, "layout.dock")?.group)
		assertEquals(BackupGroup.TVS, place(BackupScope.secretStore("tv_remote_pairing"), "pairings")?.group)
		assertEquals("Radarr", place(BackupScope.secretStore("arr_services"), "radarr")?.item)
		// A setting added later is backed up until someone decides otherwise.
		assertEquals(BackupGroup.OTHER, place(BackupScope.SETTINGS, "something.new")?.group)
	}

	@Test
	fun profilesAreCountedOnceHoweverManySettingsEachHas() {
		val keys = listOf(
			"playback.a.quality", "playback.a.trailers", "posters.title.a", "subtitles.a.look", "home.layout.a", "library.view.a.12",
			"playback.b.quality", "library.hidden.b", "library.chosen.b",
		)
		val entries = keys.map { BackupEntry.of(BackupScope.SETTINGS, it, true, BackupGroup.PROFILES)!! } +
			BackupEntry.of(BackupScope.SETTINGS, "theme.light", "x", BackupGroup.APPEARANCE)!!
		assertEquals(2, BackupScope.profileCount(entries))
		assertEquals("a", BackupScope.profileOf("library.view.a.12"))
		assertEquals("a", BackupScope.profileOf("posters.sortValue.a"))
		assertNull(BackupScope.profileOf("theme.light"))
	}

	private fun entries(vararg pairs: Triple<String, String, Any>) =
		pairs.map { (store, key, value) -> BackupScope.entry(store, key, value)!! }

	@Test
	fun integrationsAreReadFromAndroidsStoresInTheFormBothPlatformsShare() {
		val found = BackupScope.integrationsFrom(
			entries(
				Triple(BackupScope.SETTINGS, "marquee.seerr.address", "https://seerr.example"),
				Triple(BackupScope.SETTINGS, "marquee.seerr.pulsarr", true),
				Triple(BackupScope.secretStore("seerr"), "marquee.seerr.key", "seerr-key"),
				Triple(BackupScope.secretStore("arr_services"), "radarr", """{"address":"https://radarr.example","apiKey":"r-key"}"""),
				Triple(BackupScope.secretStore("homelabber"), "homelabber", """{"address":"https://hl.example","token":"t"}"""),
				Triple(BackupScope.secretStore("diagnostics"), "ntfy.server", "https://ntfy.example"),
				Triple(BackupScope.secretStore("diagnostics"), "ntfy.topic", "marquee"),
				// Sonarr's key is missing: an integration without what it needs is left out.
				Triple(BackupScope.secretStore("arr_services"), "sonarr", """{"address":"https://sonarr.example"}"""),
			),
		)
		assertEquals(BackupIntegration("https://seerr.example", "seerr-key", mapOf("pulsarr" to "true")), found["seerr"])
		assertEquals(BackupIntegration("https://radarr.example", "r-key"), found["radarr"])
		assertEquals(BackupIntegration("https://hl.example", "t"), found["homeLabber"])
		assertEquals(BackupIntegration("https://ntfy.example", null, mapOf("topic" to "marquee")), found["diagnostics"])
		assertFalse("sonarr" in found)
	}

	@Test
	fun anIphonesIntegrationsComeToTheSameStoresAndBackAgain() {
		val phone = mapOf(
			"seerr" to BackupIntegration("https://seerr.example", "k", mapOf("pulsarr" to "false")),
			"bazarr" to BackupIntegration("https://bazarr.example", "b"),
			"speechDock" to BackupIntegration("https://speech.example"),
			"sonarr" to BackupIntegration("https://sonarr.example", null),
		)
		val entries = BackupScope.entriesFrom(phone)
		assertTrue(entries.all { BackupScope.covers(it) && it.group == BackupGroup.INTEGRATIONS })
		// Sonarr had no key, so nothing of it is written.
		assertTrue(entries.none { it.key == "sonarr" })
		val again = BackupScope.integrationsFrom(entries)
		assertEquals(phone["seerr"], again["seerr"])
		assertEquals(phone["bazarr"], again["bazarr"])
		assertEquals(phone["speechDock"], again["speechDock"])
		assertNotNull(entries.single { it.key == "marquee.seerr.pulsarr" }.bool())
	}

	@Test
	fun theWireFormIsCoresEntryShape() {
		val entry = BackupEntry.of(BackupScope.SETTINGS, "playback.a.quality", 8, BackupGroup.PROFILES)!!
		val json = BackupJson.encodeToString(BackupEntry.serializer(), entry)
		assertEquals("""{"store":"settings","key":"playback.a.quality","group":"profiles","kind":"int","value":8}""", json)
		assertEquals(entry, BackupJson.decodeFromString(BackupEntry.serializer(), json))
		val set = BackupEntry.of(BackupScope.SETTINGS, "library.hidden.a", setOf("b", "a"))!!
		assertEquals(setOf("a", "b"), set.strings())
		assertEquals("strings", set.kind)
		assertNull(BackupEntry.of(BackupScope.SETTINGS, "x", ByteArray(1)))
	}
}

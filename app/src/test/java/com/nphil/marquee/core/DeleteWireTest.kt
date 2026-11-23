package com.nphil.marquee.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeleteWireTest {
	private val reply = SeerrReply.serializer(Deletion.serializer())

	@Test
	fun theServersAnswerCarriesTheTicketTheCleanupTakes() {
		val json = """
			{"value":{"outcome":"deleted","watchlist":"unchanged","arr":"notConnected","requests":"notConnected",
			"ticket":{"ratingKey":"108","kind":"movie","title":"Sintel","tmdbID":45745}}}
		""".trimIndent()
		val first = CoreJson.decodeFromString(reply, json).value
		assertEquals(DeleteOutcome.Deleted, first.outcome)
		assertEquals("108", first.ticket!!.getValue("ratingKey").jsonPrimitive.content)
	}

	@Test
	fun eachStepOfACleanupDecodesAndASingleVersionAnswerNeedsNone() {
		val json = """
			{"value":{"outcome":"deleted","watchlist":"removed","guid":"plex://movie/abc","arr":"unmatched","requests":"failed",
			"ticket":{"ratingKey":"108"}},"session":"connect.sid=abc"}
		""".trimIndent()
		val result = CoreJson.decodeFromString(reply, json)
		assertEquals("connect.sid=abc", result.session)
		with(result.value) {
			assertEquals(WatchlistCleanup.Removed, watchlist)
			assertEquals("plex://movie/abc", guid)
			assertEquals(ServiceCleanup.Unmatched, arr)
			assertEquals(ServiceCleanup.Failed, requests)
			assertNotNull(ticket)
		}
		val version = CoreJson.decodeFromString(Deletion.serializer(), """{"outcome":"deleted","watchlist":"unchanged"}""")
		assertEquals(ServiceCleanup.NotConnected, version.arr)
		assertNull(version.ticket)
	}

	@Test
	fun theConnectionsGoOverTheBridgeOnlyWhereSaved() {
		val none = buildJsonObject { putServices(DeleteServices(accountToken = null, radarr = null, sonarr = null, seerr = null)) }
		assertEquals(JsonObject(emptyMap()), none)

		val services = DeleteServices(
			accountToken = "plex-token",
			radarr = ArrLink("https://radarr.local", "a".repeat(32)),
			sonarr = null,
			seerr = SeerrLink("https://seerr.local", apiKey = "seerr-key", plexToken = null, session = null),
		)
		val sent = buildJsonObject { putServices(services) }
		assertEquals(setOf("token", "radarr", "seerr"), sent.keys)
		assertEquals("plex-token", sent.getValue("token").jsonPrimitive.content)
		assertEquals("https://radarr.local", sent.getValue("radarr").jsonObject.getValue("address").jsonPrimitive.content)
		assertEquals("a".repeat(32), sent.getValue("radarr").jsonObject.getValue("apiKey").jsonPrimitive.content)
		assertEquals("seerr-key", sent.getValue("seerr").jsonObject.getValue("apiKey").jsonPrimitive.content)
	}

	@Test
	fun aRetryCarriesWhatEachStepCameTo() {
		val failed = Deletion(
			DeleteOutcome.Deleted, WatchlistCleanup.Removed, guid = "plex://movie/abc",
			arr = ServiceCleanup.Failed, requests = ServiceCleanup.Removed, ticket = buildJsonObject { put("ratingKey", "108") },
		)
		val steps = failed.steps()
		assertEquals(setOf("watchlist", "guid", "arr", "requests"), steps.keys)
		assertEquals("removed", steps.getValue("watchlist").jsonPrimitive.content)
		assertEquals("plex://movie/abc", steps.getValue("guid").jsonPrimitive.contentOrNull)
		assertEquals("failed", steps.getValue("arr").jsonPrimitive.content)
		assertEquals("removed", steps.getValue("requests").jsonPrimitive.content)
	}
}

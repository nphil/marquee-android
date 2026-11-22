package com.nphil.marquee.fakeplex

import com.nphil.marquee.fakeplex.router.FakePlexRouter
import com.nphil.marquee.fakeplex.router.FakeReply
import com.nphil.marquee.fakeplex.router.FakeRequest
import com.nphil.marquee.fakeplex.router.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The fixture rules of the fake Plex, run against the real fixture tree without an emulator. */
class FakePlexRouterTest {
	private val root = File(System.getProperty("marquee.fakeplex")!!)
	private val router = FakePlexRouter(Fixtures { path -> File(root, path).takeIf(File::isFile)?.readBytes() })

	private fun get(
		path: String, vararg query: Pair<String, String>, host: String = "192.0.2.10", method: String = "GET",
		headers: Map<String, String> = emptyMap(),
	): FakeReply = router.answer(FakeRequest(method, host, path, query.toList(), headers))!!

	private fun JsonObject.metadata(): List<JsonObject> =
		((this["MediaContainer"] as JsonObject)["Metadata"] as JsonArray).map { it.jsonObject }

	private fun item(key: String): JsonObject =
		Json.parseToJsonElement(get("/library/metadata/$key").body.decodeToString()).jsonObject.metadata().single()

	private fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.int

	private fun parsed(reply: FakeReply) = Json.parseToJsonElement(reply.body.decodeToString())

	private fun fixture(path: String) = Json.parseToJsonElement(File(root, path).readText())

	@Test
	fun aQueryVariantBeatsThePlainFixture() {
		val noPin = get("/api/v2/home/users/fake-alex/switch", host = "plex.tv", method = "POST")
		val pin = get("/api/v2/home/users/fake-alex/switch", "pin" to "1234", host = "plex.tv", method = "POST")
		assertEquals(403, noPin.status)
		assertEquals(fixture("plextv/api/v2/home/users/fake-alex/switch.json").jsonObject - "__status", parsed(noPin))
		assertEquals(200, pin.status)
		assertEquals(fixture("plextv/api/v2/home/users/fake-alex/switch@pin=1234.json"), parsed(pin))
	}

	@Test
	fun aWriteSwitchesToTheAfterFixture() {
		val before = get("/library/metadata/212").body.decodeToString()
		assertEquals(200, get("/api/providers/episodes", method = "POST").status)
		assertNotEquals(before, get("/library/metadata/212").body.decodeToString())
	}

	@Test
	fun scrobbleAndUnscrobbleChangeWhatLaterReadsShow() {
		// Sintel starts part watched, with a resume point.
		assertNull(item("108").number("viewCount"))
		assertNotNull(item("108").number("viewOffset"))

		get("/:/scrobble", "identifier" to "com.plexapp.plugins.library", "key" to "108")
		assertEquals(1, item("108").number("viewCount"))
		assertNull(item("108").number("viewOffset"))

		get("/:/unscrobble", "identifier" to "com.plexapp.plugins.library", "key" to "108")
		assertEquals(0, item("108").number("viewCount"))
	}

	@Test
	fun aShowMarkedWatchedMarksItsEpisodes() {
		get("/:/scrobble", "key" to "201")
		val episodes = Json.parseToJsonElement(get("/library/metadata/202/children").body.decodeToString()).jsonObject.metadata()
		assertTrue(episodes.all { it.number("viewCount") == 1 && it["viewOffset"] == null })
	}

	@Test
	fun aBinaryFileAnswersASingleByteRange() {
		val whole = File(root, "pms/library/parts/1011/file.mp4.bin").readBytes()
		val path = "/library/parts/1011/file.mp4"
		assertArrayEquals(whole, get(path).body)

		val head = get(path, headers = mapOf("Range" to "bytes=0-3"))
		assertEquals(206, head.status)
		assertEquals("bytes 0-3/${whole.size}", head.headers["Content-Range"])
		assertArrayEquals(whole.copyOfRange(0, 4), head.body)

		val tail = get(path, headers = mapOf("range" to "bytes=-4"))
		assertEquals(206, tail.status)
		assertArrayEquals(whole.copyOfRange(whole.size - 4, whole.size), tail.body)

		val beyond = get(path, headers = mapOf("Range" to "bytes=${whole.size}-"))
		assertEquals(416, beyond.status)
		assertEquals("bytes */${whole.size}", beyond.headers["Content-Range"])
	}

	@Test
	fun deleteAlwaysSucceedsAndWritesWithoutFixturesAnswerEmpty() {
		assertEquals(200, get("/library/metadata/nothing-here", method = "DELETE").status)
		assertEquals("{}", get("/library/sections/9/refresh", method = "POST").body.decodeToString())
		assertEquals(404, get("/library/metadata/nothing-here").status)
	}

	@Test
	fun artComesFromTheFixtureFilesAndUnreachableHostsFail() {
		val art = File(root, "art").listFiles { f -> f.extension == "jpg" }!!.first()
		val viaTranscode = get("/photo/:/transcode", "url" to "/art/${art.name}", "width" to "100")
		assertEquals("image/jpeg", viaTranscode.contentType)
		assertArrayEquals(art.readBytes(), viaTranscode.body)
		assertArrayEquals(art.readBytes(), get("/art/${art.name}", host = "metadata-static.plex.tv").body)

		assertNull(router.answer(FakeRequest("GET", "192.0.2.20", "/identity")))
		val identity = parsed(get("/identity", host = "203.0.113.20")).jsonObject["MediaContainer"]!!.jsonObject["machineIdentifier"]
		assertEquals(JsonPrimitive("fake-cabin"), identity)
	}
}

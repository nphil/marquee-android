package com.nphil.marquee

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.diagnostics.DiagReportResult
import com.nphil.marquee.diagnostics.DiagRequestLog
import com.nphil.marquee.diagnostics.DiagTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The diagnostic log on a device with the real Swift bridge, against a local stand-in for the ntfy server (the real
 * topic is never touched): a warning goes out live, a Plex request is logged without its query, and a report goes out
 * with its journal, screenshot and summary. A secret in an event, a note or a URL must reach none of it.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsBridgeTest {
	private class Seen(val method: String, val path: String, val contentType: String?, val body: ByteArray) {
		val text: String get() = body.decodeToString()
	}

	private val ntfy = MockWebServer().apply {
		dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest) = MockResponse()
				.setHeader("Content-Type", "application/json")
				.setBody("""{"id":"m1","event":"message","topic":"$TOPIC"}""")
		}
	}
	private val seen = mutableListOf<Seen>()

	@After
	fun tearDown() {
		runBlocking { Diag.update { it.copy(server = "", topic = "", token = "", detailed = false) } }
		ntfy.shutdown()
	}

	/** The first request, already received or still to come, that [match]es; null when none does within 30 seconds. */
	private fun find(match: (Seen) -> Boolean): Seen? {
		seen.firstOrNull(match)?.let { return it }
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
		while (true) {
			val left = deadline - System.nanoTime()
			if (left <= 0) return null
			val request = ntfy.takeRequest(left, TimeUnit.NANOSECONDS) ?: return null
			val next = Seen(request.method.orEmpty(), request.path.orEmpty(), request.getHeader("Content-Type"), request.body.readByteArray())
			seen += next
			if (match(next)) return next
		}
	}

	@Test
	fun theLogGoesLiveAndAReportGoesWithItsJournalAndNoSecrets() = runBlocking {
		// What the bridge sends goes through a client that logs Plex requests, as the app's own does.
		val client = OkHttpClient.Builder().addInterceptor(DiagRequestLog()).build()
		NativeCore.install(NativeTransport(client))
		ntfy.start(InetAddress.getByName("127.0.0.1"), 0)
		val base = "http://127.0.0.1:${ntfy.port}"
		Diag.update { it.copy(server = base, topic = TOPIC, token = "tk_bridge_test_0123456789", live = true, detailed = true) }

		assertEquals(DiagTest.Sent, Diag.test())

		Diag.warning(DiagCategory.App, "bridge.test.warning", "url" to "https://plex.example/library?X-Plex-Token=$SECRET")
		val live = find { it.method == "POST" && it.text.contains("bridge.test.warning") }
		assertNotNull("The warning did not go out live.", live)
		assertTrue(live!!.path.startsWith("/$TOPIC"))
		assertFalse(live.text.contains(SECRET))

		client.newCall(
			Request.Builder().url("$base/library/sections?X-Plex-Token=$SECRET").header("X-Plex-Client-Identifier", "bridge-test").build(),
		).execute().close()
		val journal = Diag.journal()
		assertTrue(journal.contains("bridge.test.warning"))
		assertTrue(journal.lines().any { it.contains(" net request ") && it.contains("/library/sections") && it.contains("status=200") })
		assertTrue(journal.lines().none { it.contains(" net request ") && it.contains(TOPIC) })
		assertFalse(journal.contains(SECRET))

		val screenshot = byteArrayOf(1, 2, 3, 4, 5)
		val outcome = Diag.report(
			note = "The spinner never stops. Authorization: Bearer $BEARER",
			source = "settings",
			details = "Phone: Test tablet\nScreen: tab=Library path=root sheets=-",
			screen = "tab=Library path=root sheets=-",
			screenshot = screenshot,
		)
		val sent = outcome as? DiagReportResult.Sent ?: error("The report was not sent: $outcome")
		assertTrue(sent.attached)

		val log = find { it.method == "PUT" && it.text.contains("==== Journal ====") }
		assertNotNull("The journal was not attached.", log)
		assertTrue(log!!.text.startsWith("Marquee report ${sent.id}"))
		assertTrue(log.text.contains("Phone: Test tablet"))
		assertTrue(log.text.contains("bridge.test.warning"))

		val shot = find { it.method == "PUT" && it.contentType == "image/jpeg" }
		assertNotNull("The screenshot was not sent.", shot)
		assertArrayEquals(screenshot, shot!!.body)

		val summary = find { it.method == "POST" && it.text.contains("Report ${sent.id} from") }
		assertNotNull("The summary was not sent.", summary)
		assertTrue(summary!!.text.contains("Note: The spinner never stops."))
		assertTrue(summary.text.contains("Screen: tab=Library path=root sheets=-"))

		val everything = seen.joinToString("\n") { it.text }
		assertFalse(everything.contains(SECRET))
		assertFalse(everything.contains(BEARER))
	}

	private companion object {
		const val TOPIC = "marquee-test"
		const val SECRET = "sup3rs3cretvalue"
		const val BEARER = "abcdef123456"
	}
}

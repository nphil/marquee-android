package com.nphil.marquee.live

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DirectTunerTest {
	private val server = MockWebServer()
	private val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
	private val uuid = "device://tv.plex.grabbers.hdhomerun/0a1b2c3d"

	@After
	fun close() = server.shutdown()

	@Test
	fun anotherDeviceAtTheSameAddressIsNotTuned() = runBlocking {
		server.enqueue(MockResponse().setBody("""{"DeviceID":"DEADBEEF"}"""))
		assertNull(TunerCandidate.discover(http, server.url("/"), uuid, emptySet()))
		assertEquals("/discover.json", nextRequest())
		assertEquals(1, server.requestCount)
	}

	@Test
	fun anExistingPlexHolderIsDetectedWithoutOpeningAStream() = runBlocking {
		val tuner = candidate()
		server.enqueue(MockResponse().setBody("""{"holder":{"ip":"192.0.2.20","name":"Plex","channel":"4.1"}}"""))
		assertEquals(TunerAvailability.Held("The tuner is in use by Plex."), tuner.availability())
		assertEquals("/status.json", nextRequest())
		assertEquals(3, server.requestCount)
	}

	@Test
	fun successfulHttpWithNoClaimIsATerminalSlateNotAFallback() = runBlocking {
		val tuner = candidate()
		server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("slate"))
		server.enqueue(MockResponse().setBody("""{"holder":null}"""))
		val request = streamRequest()
		val feed = checkNotNull(tuner.open(request.url.toString(), "2.1"))
		val call = feed.newCall(request)
		call.execute().use { assertEquals(200, it.code) }
		val recovery = feed.recover(DirectProblem.Fallback) as DirectProblem.Refused
		assertEquals("The tuner is unavailable; the TV may be using it.", recovery.message)
		assertEquals("no_claim", recovery.reason)
		assertTrue(call.isCanceled())
		assertEquals("/auto/v2.1", nextRequest())
		assertEquals("/status.json", nextRequest())
		assertEquals(4, server.requestCount)
	}

	@Test
	fun aTvTakeoverEndsEvenAConfirmedStreamWithoutPlexFallback() = runBlocking {
		val tuner = candidate()
		server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("terminal slate"))
		server.enqueue(MockResponse().setBody(ourClaim))
		val request = streamRequest()
		val feed = checkNotNull(tuner.open(request.url.toString(), "2.1"))
		val call = feed.newCall(request)
		call.execute().use { assertEquals("terminal slate", it.body?.string()) }
		assertNull(feed.refusal)
		val recovery = feed.recover(feed.endProblem()) as DirectProblem.Refused
		assertEquals("tuner_ended", recovery.reason)
		assertTrue(call.isCanceled())
		assertEquals("/auto/v2.1", nextRequest())
		assertEquals("/status.json", nextRequest())
		assertEquals(4, server.requestCount)
	}

	@Test
	fun aConfirmedStreamIoFailureReleasesBeforeItsOnlyPlexAttempt() = runBlocking {
		val tuner = candidate()
		server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("video".repeat(100))
			.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
		server.enqueue(MockResponse().setBody(ourClaim))
		server.enqueue(MockResponse().setBody("""{"holder":null}"""))
		server.enqueue(MockResponse().setBody("playlist"))
		val request = streamRequest()
		val feed = checkNotNull(tuner.open(request.url.toString(), "2.1"))
		val call = feed.newCall(request)
		val failure = call.execute().use { runCatching { it.body?.bytes() }.exceptionOrNull() }
		assertTrue(failure is IOException)
		assertNull(feed.refusal)
		assertEquals("/auto/v2.1", nextRequest())
		assertEquals("/status.json", nextRequest())
		val problem = directProblem(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, failure)
		repeat(2) {
			if (feed.recover(problem) == DirectProblem.Fallback) {
				assertTrue(call.isCanceled())
				assertEquals(TunerAvailability.Free, tuner.afterRelease())
				http.newCall(Request.Builder().url(server.url("/plex-tune")).build()).execute().close()
			}
		}
		assertEquals("/status.json", nextRequest())
		assertEquals("/plex-tune", nextRequest())
		assertEquals(6, server.requestCount)
	}

	@Test
	fun stoppingAnOpeningStreamCancelsTheTuneSocket() = runBlocking {
		val tuner = candidate()
		server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
		val request = streamRequest()
		val feed = checkNotNull(tuner.open(request.url.toString(), "2.1"))
		val finished = CompletableFuture<Boolean>()
		val call = feed.newCall(request)
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) { finished.complete(call.isCanceled()) }
			override fun onResponse(call: Call, response: Response) { response.close(); finished.complete(false) }
		})
		assertTrue(feed.newCall(request).isCanceled())
		assertEquals("/auto/v2.1", nextRequest())
		feed.stop()
		assertTrue(finished.get(2, TimeUnit.SECONDS))
		assertTrue(feed.newCall(request).isCanceled())
		assertEquals(3, server.requestCount)
	}

	@Test
	fun bookingFromGuideWaitsForTheStoppedStreamClaim() = runBlocking {
		val tuner = candidate()
		val request = streamRequest()
		val feed = checkNotNull(tuner.open(request.url.toString(), "2.1"))
		val call = feed.newCall(request)
		server.enqueue(MockResponse().setBody("video"))
		server.enqueue(MockResponse().setBody(ourClaim))
		call.execute().close()
		feed.stop()
		assertTrue(call.isCanceled())

		val release = DvrTunerRelease()
		release.stopped(tuner)
		server.enqueue(MockResponse().setResponseCode(503))
		assertFalse(release.beforeRecord { error("Must not book while release is unverified") })
		server.enqueue(MockResponse().setBody(ourClaim))
		server.enqueue(MockResponse().setBody("""{"holder":null}"""))
		server.enqueue(MockResponse())
		assertTrue(release.beforeRecord {
			val write = Request.Builder().url(server.url("/media/subscriptions"))
				.method("POST", okhttp3.RequestBody.create(null, ByteArray(0))).build()
			http.newCall(write).execute().close()
		})
		assertEquals("/auto/v2.1", nextRequest())
		repeat(4) { assertEquals("/status.json", nextRequest()) }
		assertEquals("/media/subscriptions", nextRequest())
	}

	@Test
	fun tunerKeepsTheRemoteRefusalReason() {
		assertEquals("the TV is in use: Live TV is in front",
			tunerHttpRefusal(503, "805 All Tuners In Use", "the TV is in use: Live TV is in front".encodeToByteArray()))
		assertEquals("The tuner is already in use.", tunerHttpRefusal(503, null, ByteArray(0)))
	}

	@Test
	fun onlyAConnectionThatFailedOrTimedOutMarksTheTunerLost() {
		assertTrue(tunerLost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
		assertTrue(tunerLost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
		assertFalse(tunerLost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
		assertFalse(tunerLost(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
	}

	@Test
	fun permissionDenialSkipsDiscoveryButOlderAndroidStillProbes() = runBlocking {
		val denied = permitsDirectTuner(37) { false }
		assertNull(probeTunerIfAllowed(denied) { candidate() })
		assertEquals(0, server.requestCount)
		assertTrue(permitsDirectTuner(37) { true })
		val older = permitsDirectTuner(36) { error("Android 16 has no local-network permission to check") }
		assertTrue(probeTunerIfAllowed(older) { candidate() }?.isLuma == true)
		assertEquals(2, server.requestCount)
	}

	private suspend fun candidate(): TunerCandidate {
		server.enqueue(MockResponse().setBody("""{"DeviceID":"0A1B2C3D","DeviceAuth":"lumatv","TunerCount":1}"""))
		server.enqueue(MockResponse().setBody("""[{"GuideNumber":"2.1","GuideName":"WKTV"}]"""))
		val tuner = checkNotNull(TunerCandidate.discover(http, server.url("/"), uuid, setOf("127.0.0.1")))
		assertEquals(1, tuner.count)
		assertTrue(tuner.isLuma)
		assertEquals("WKTV", tuner.lineup("2.1")?.get("GuideName")?.toString()?.trim('"'))
		assertEquals("/discover.json", nextRequest())
		assertEquals("/lineup.json", nextRequest())
		return tuner
	}

	private fun nextRequest() = server.takeRequest(1, TimeUnit.SECONDS)?.path
	private fun streamRequest() = Request.Builder().url(server.url("/auto/v2.1")).header("User-Agent", "Marquee").build()

	private val ourClaim = """{"holder":{"ip":"127.0.0.1","name":"Marquee","channel":"2.1"}}"""
}

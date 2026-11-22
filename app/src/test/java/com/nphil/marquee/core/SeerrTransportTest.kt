package com.nphil.marquee.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The sign-in cookie Seerr's Plex sign-in sets travels through the transport by header, not by a cookie jar. */
class SeerrTransportTest {
	private val server = MockWebServer()
	private val headers = ArrayBlockingQueue<String>(1)
	private val transport = NativeTransport(
		OkHttpClient(),
		object : NativeTransport.Sink {
			override fun response(id: Long, status: Int, headers: ByteArray, body: ByteArray) {
				this@SeerrTransportTest.headers.put(headers.decodeToString())
			}

			override fun failure(id: Long, code: Int) = error("failed $code")
		},
	)

	@After
	fun tearDown() = server.shutdown()

	@Test
	fun theCookieTheCallerSetsIsSentAndEveryCookieSeerrSetsComesBack() {
		server.enqueue(MockResponse().addHeader("Set-Cookie", "connect.sid=abc; Path=/").addHeader("Set-Cookie", "theme=dark"))
		val request = """{"method":"GET","url":"${server.url("/api/v1/auth/me")}","headers":{"Cookie":"connect.sid=old"},"timeoutMillis":5000}"""
		transport.send(1, request.encodeToByteArray(), null)
		val answer = headers.poll(5, TimeUnit.SECONDS) ?: error("No answer within 5 s")
		assertEquals("connect.sid=old", server.takeRequest().getHeader("Cookie"))
		assertTrue(answer, answer.contains("connect.sid=abc"))
		assertTrue(answer, answer.contains("theme=dark"))
	}

	@Test
	fun noCookieIsSentUnlessTheCallerSetsOne() {
		server.enqueue(MockResponse().addHeader("Set-Cookie", "connect.sid=abc"))
		transport.send(2, """{"method":"GET","url":"${server.url("/")}","timeoutMillis":5000}""".encodeToByteArray(), null)
		headers.poll(5, TimeUnit.SECONDS) ?: error("No answer within 5 s")
		server.takeRequest()
		server.enqueue(MockResponse())
		transport.send(3, """{"method":"GET","url":"${server.url("/")}","timeoutMillis":5000}""".encodeToByteArray(), null)
		headers.poll(5, TimeUnit.SECONDS) ?: error("No answer within 5 s")
		assertNull(server.takeRequest().getHeader("Cookie"))
	}
}

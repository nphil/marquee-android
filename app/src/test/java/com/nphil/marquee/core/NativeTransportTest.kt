package com.nphil.marquee.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NativeTransportTest {
	private val server = MockWebServer()
	private val sink = RecordingSink()
	private val transport = NativeTransport(OkHttpClient(), sink)

	@After
	fun tearDown() = server.shutdown()

	private fun request(path: String, method: String = "GET", restrictToOrigin: Boolean = false, isIdempotent: Boolean = method == "GET") =
		"""{"method":"$method","url":"${server.url(path)}","headers":{"X-Plex-Token":"t"},"timeoutMillis":5000,"restrictToOrigin":$restrictToOrigin,"isIdempotent":$isIdempotent}"""
			.encodeToByteArray()

	@Test
	fun answersWithStatusHeadersAndBody() {
		server.enqueue(MockResponse().setResponseCode(404).setHeader("X-Test", "yes").setBody("gone"))
		transport.send(1, request("/a"), null)
		val answer = sink.await()
		assertEquals(404, answer.status)
		assertTrue(answer.headers, answer.headers.contains("\"X-Test\":\"yes\""))
		assertEquals("gone", answer.body)
		assertEquals("t", server.takeRequest().getHeader("X-Plex-Token"))
	}

	@Test
	fun followsSameOriginRedirectAsBodilessGet() {
		server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/b"))
		server.enqueue(MockResponse().setBody("done"))
		transport.send(2, request("/a", method = "POST", restrictToOrigin = true), "x".encodeToByteArray())
		assertEquals("done", sink.await().body)
		server.takeRequest()
		val followed = server.takeRequest()
		assertEquals("GET", followed.method)
		assertEquals("/b", followed.path)
	}

	@Test
	fun leavesCrossOriginRedirectToTheCaller() {
		server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://elsewhere.invalid/"))
		transport.send(3, request("/a", restrictToOrigin = true), null)
		assertEquals(302, sink.await().status)
	}

	@Test
	fun refusedConnectionReportsCannotConnect() {
		val port = server.port
		server.shutdown()
		transport.send(4, """{"method":"GET","url":"http://127.0.0.1:$port/","timeoutMillis":2000}""".encodeToByteArray(), null)
		assertEquals(NativeTransport.CANNOT_CONNECT, sink.await().failure)
	}

	@Test
	fun anEmptyPostThatReachedTheServerIsNotReplayedAfterItsResponseIsLost() {
		server.enqueue(MockResponse().setBody("warm"))
		server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
		server.enqueue(MockResponse().setBody("would be a duplicate"))
		transport.send(5, request("/ready"), null)
		assertEquals("warm", sink.await().body)
		transport.send(6, request("/downloadQueue/1/add", method = "POST"), null)
		assertNotNull(sink.await().failure)
		assertEquals(2, server.requestCount)
		assertEquals("GET", server.takeRequest().method)
		val submitted = server.takeRequest()
		assertEquals("POST", submitted.method)
		assertEquals(0L, submitted.bodySize)
	}

	@Test
	fun aRetryAfterResponseCanRetryASafeReadButNotAnEmptyPost() {
		server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
		server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
		server.enqueue(MockResponse().setBody("read retried"))
		transport.send(7, request("/downloadQueue/1/add", method = "POST"), null)
		assertEquals(503, sink.await().status)
		transport.send(8, request("/downloadQueue/1/items/10"), null)
		assertEquals("read retried", sink.await().body)
		assertEquals(3, server.requestCount)
	}

	private class RecordingSink : NativeTransport.Sink {
		class Answer(val status: Int = 0, val headers: String = "", val body: String = "", val failure: Int? = null)

		private val answers = LinkedBlockingQueue<Answer>()

		override fun response(id: Long, status: Int, headers: ByteArray, body: ByteArray) {
			answers.put(Answer(status, headers.decodeToString(), body.decodeToString()))
		}

		override fun failure(id: Long, code: Int) {
			answers.put(Answer(failure = code))
		}

		fun await(): Answer = answers.poll(5, TimeUnit.SECONDS) ?: error("No answer within 5 s")
	}
}

package com.nphil.marquee.playback

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class RangeReaderTest {
	private val body = ByteArray(600_000) { (it % 251).toByte() }
	private val counter = AtomicLong()
	private val client = OkHttpClient()
	private val server = MockWebServer()
	private var honoursRanges = true

	@Before
	fun serveTheFile() {
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				val wanted = request.getHeader("Range")?.removePrefix("bytes=")?.split("-")
				if (!honoursRanges || wanted == null) return MockResponse().setBody(Buffer().write(body))
				val first = wanted[0].toInt()
				if (first >= body.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
				val last = minOf(wanted[1].toInt(), body.size - 1)
				return MockResponse()
					.setResponseCode(206)
					.setHeader("Content-Range", "bytes $first-$last/${body.size}")
					.setBody(Buffer().write(body, first, last - first + 1))
			}
		}
		server.start()
	}

	@After
	fun stopServing() {
		server.shutdown()
		client.connectionPool.evictAll()
		client.dispatcher.executorService.shutdown()
	}

	private fun reader(headers: Map<String, String> = emptyMap()) = RangeReader(client, server.url("/film.mkv").toString(), headers, counter)

	@Test
	fun readsWhatIsAskedAcrossBlocksWithOneRequestForEach() {
		val reader = reader(mapOf("X-Plex-Token" to "t"))
		val piece = ByteArray(1_000)
		assertEquals(body.size.toLong(), reader.size())

		assertEquals(1_000, reader.readAt(100, piece, 0, 1_000))
		assertArrayEquals(body.copyOfRange(100, 1_100), piece)
		assertEquals(1, server.requestCount)

		// Across the end of the first block of 256 KB.
		assertEquals(1_000, reader.readAt(262_000, piece, 0, 1_000))
		assertArrayEquals(body.copyOfRange(262_000, 263_000), piece)
		assertEquals(2, server.requestCount)
		assertEquals(2L * 262_144, counter.get())

		val first = server.takeRequest()
		assertEquals("bytes=0-262143", first.getHeader("Range"))
		assertEquals("t", first.getHeader("X-Plex-Token"))
	}

	@Test
	fun endsAtTheEndOfTheFile() {
		val piece = ByteArray(100)
		val reader = reader()
		assertEquals(10, reader.readAt(599_990, piece, 0, 100))
		assertArrayEquals(body.copyOfRange(599_990, 600_000), piece.copyOf(10))
		assertEquals(-1, reader.readAt(600_000, piece, 0, 100))
		assertEquals(-1, reader.readAt(900_000, piece, 0, 100))
		// The first request past the end of a file whose length is not yet known is answered 416.
		assertEquals(-1, reader().readAt(900_000, piece, 0, 100))
	}

	@Test
	fun aServerThatIgnoresRangesIsAnError() {
		honoursRanges = false
		assertThrows(IOException::class.java) { reader().readAt(0, ByteArray(10), 0, 10) }
	}

	@Test
	fun aReaderThatWasLetGoReadsNoMore() {
		val reader = reader()
		reader.abort()
		assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(10), 0, 10) }
		assertEquals(0, server.requestCount)
	}
}

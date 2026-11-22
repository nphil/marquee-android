package com.nphil.marquee.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

class DiagRequestLogTest {
	private class Line(val level: DiagLevel, val name: String, val fields: Map<String, Any?>)

	private val lines = mutableListOf<Line>()
	private val client = OkHttpClient.Builder()
		.addInterceptor(DiagRequestLog(emit = { level, name, fields -> lines += Line(level, name, fields.toMap()) }))
		.build()

	private fun photo(port: Int) = Request.Builder()
		.url("http://127.0.0.1:$port/photo/:/transcode?width=300&url=%2Flibrary%2Fmetadata%2F1%2Fthumb")
		.header("X-Plex-Client-Identifier", "test")
		.build()

	@Test
	fun aCallTheAppCancelledIsOneQuietLineWithNothingToSeparateItFromTheNext() {
		val call = client.newCall(photo(port = 1))
		call.cancel()
		assertThrows(IOException::class.java) { call.execute() }

		val line = lines.single()
		assertEquals(DiagLevel.Debug, line.level)
		assertEquals("request.canceled", line.name)
		// Neither a time nor an error text, so a run of cancelled calls is a run of identical lines.
		assertEquals(mapOf("method" to "GET", "url" to "http://127.0.0.1:1/photo/:/transcode"), line.fields)
	}

	@Test
	fun aFailureThatNobodyCancelledIsStillANotice() {
		val closed = ServerSocket(0).use { it.localPort }
		assertThrows(IOException::class.java) { client.newCall(photo(closed)).execute() }

		val line = lines.single()
		assertEquals(DiagLevel.Notice, line.level)
		assertEquals("request", line.name)
		assertTrue(line.fields.keys.containsAll(listOf("method", "url", "ms", "error")))
	}
}

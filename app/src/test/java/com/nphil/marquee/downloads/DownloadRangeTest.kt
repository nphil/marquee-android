package com.nphil.marquee.downloads

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRangeTest {
	@Test
	fun aPartialReplyMustMatchTheDurablePrefixExactly() {
		assertEquals(DownloadRange(12, 24, 48), DownloadRange.read(206, 12, 24, "bytes 12-35/48"))
		assertNull(DownloadRange.read(206, 12, 24, "bytes 13-36/48"))
		assertNull(DownloadRange.read(206, 12, 24, "bytes 12-34/48"))
		assertNull(DownloadRange.read(206, 12, -1, "bytes 12-48/48"))
		assertNull(DownloadRange.read(206, 12, -1, "bytes 12-35/*"))
	}

	@Test
	fun aFullReplyAlwaysReplacesThePartialFile() {
		assertEquals(DownloadRange(0, 48, 48), DownloadRange.read(200, 12, 48, null))
		assertEquals(DownloadRange(0, null, null), DownloadRange.read(200, 12, -1, null))
		assertNull(DownloadRange.read(416, 48, 0, "bytes */48"))
	}

	@Test
	fun aWeakOrAmbiguousTagCannotAuthorizeAppending() {
		val file = DownloadValidator.resource("https://plex.example.test/media")
		assertEquals("\"v1\"", DownloadValidator.read(headersOf("ETag", "\"v1\""), file)?.value)
		assertNull(DownloadValidator.read(headersOf("ETag", "W/\"v1\""), file))
		assertNull(DownloadValidator.read(headersOf("ETag", "v1"), file))
		assertNull(DownloadValidator.read(headersOf("ETag", "\"v1\"", "ETag", "\"v2\""), file))
	}

	@Test
	fun modificationTimeMustBeStrongEnoughToAuthorizeAppending() {
		val file = DownloadValidator.resource("https://plex.example.test/media")
		val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
		assertEquals(modified, DownloadValidator.read(headersOf("Last-Modified", modified, "Date", "Wed, 21 Oct 2015 07:29:00 GMT"), file)?.value)
		assertNull(DownloadValidator.read(headersOf("Last-Modified", modified, "Date", "Wed, 21 Oct 2015 07:28:59 GMT"), file))
		assertNull(DownloadValidator.read(headersOf("Last-Modified", modified), file))
	}

	@Test
	fun transferPreservesTheNonAsciiPlexDeviceName() {
		val device = "Nitin’s Pad"
		val request = downloadRequest("https://plex.example.test/media", mapOf("X-Plex-Device-Name" to device)).build()
		assertEquals(device, request.header("X-Plex-Device-Name"))
	}
}

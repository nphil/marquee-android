package com.nphil.marquee.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexImageUrlTest {
	private val base = "https://10-0-0-2.abc.plex.direct:32400/"

	// The same request Core's PlexImageURL test makes, so both apps ask the server for the same bytes.
	@Test
	fun asksTheServerForTheDisplayedPixels() {
		assertEquals(
			"https://10-0-0-2.abc.plex.direct:32400/photo/:/transcode?width=302&height=450&minSize=1&upscale=1" +
				"&url=%2Flibrary%2Fmetadata%2F12%2Fthumb%2F1699",
			PlexImageUrl.transcoded("/library/metadata/12/thumb/1699", base, 302, 450),
		)
	}

	@Test
	fun leavesTheTokenOutOfTheUrl() {
		val url = PlexImageUrl.transcoded("/library/metadata/12/thumb/1699", base, 100, 150)!!
		assertFalse(url.contains("Token", ignoreCase = true))
	}

	@Test
	fun sendsAnAbsoluteUrlThroughTheSameTranscode() {
		val url = PlexImageUrl.transcoded("https://metadata-static.plex.tv/a/b.jpg", base, 80, 120)!!
		assertTrue(url.endsWith("&url=https%3A%2F%2Fmetadata-static.plex.tv%2Fa%2Fb.jpg"))
	}

	@Test
	fun givesNothingForAMissingPathOrSize() {
		assertNull(PlexImageUrl.transcoded("", base, 100, 150))
		assertNull(PlexImageUrl.transcoded("/library/metadata/12/thumb", base, 0, 150))
		assertNull(PlexImageUrl.transcoded("/library/metadata/12/thumb", "not a url", 100, 150))
	}
}

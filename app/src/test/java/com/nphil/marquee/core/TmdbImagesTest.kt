package com.nphil.marquee.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbImagesTest {
	@Test
	fun theSmallestSizeThatIsWideEnoughIsAsked() {
		assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", TmdbImages.url("/a.jpg", 300))
		assertEquals("https://image.tmdb.org/t/p/w154/a.jpg", TmdbImages.url("/a.jpg", 154))
		assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", TmdbImages.url("/a.jpg", 343 - 1))
		assertEquals("https://image.tmdb.org/t/p/w780/a.jpg", TmdbImages.url("/a.jpg", 780))
	}

	@Test
	fun tooWideTakesTheOriginalAndNoPathTakesNothing() {
		assertEquals("https://image.tmdb.org/t/p/original/a.jpg", TmdbImages.url("/a.jpg", 1200))
		assertNull(TmdbImages.url(null, 100))
		assertNull(TmdbImages.url("a.jpg", 100))
	}
}

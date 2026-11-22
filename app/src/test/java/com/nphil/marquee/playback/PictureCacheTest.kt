package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureCacheTest {
	@Test
	fun theLeastRecentlyUsedGoFirstOncePastTheBudget() {
		val cache = PictureCache<String>(12) { it.length }
		cache.put(1, "aaaa")
		cache.put(2, "bbbb")
		cache.put(3, "cccc")
		// Using 1 makes 2 the one used longest ago.
		assertEquals("aaaa", cache.nearest(1, 0))
		cache.put(4, "dddd")

		assertFalse(cache.contains(2))
		assertTrue(cache.contains(1) && cache.contains(3) && cache.contains(4))
	}

	@Test
	fun theNewestStaysEvenWhenItIsLargerThanTheBudget() {
		val cache = PictureCache<String>(4) { it.length }
		cache.put(1, "aa")
		cache.put(2, "a picture larger than the budget")

		assertFalse(cache.contains(1))
		assertTrue(cache.contains(2))
	}

	@Test
	fun answersWithTheNearestPictureWithinReach() {
		val cache = PictureCache<String>(100) { it.length }
		cache.put(2, "two")
		cache.put(12, "twelve")

		assertEquals("two", cache.nearest(2, 0))
		// Level between the two: the earlier.
		assertEquals("two", cache.nearest(7, 5))
		assertEquals("twelve", cache.nearest(8, 5))
		assertNull(cache.nearest(8, 3))
		assertNull(cache.nearest(40, 6))
	}
}

package com.nphil.marquee.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paging rules of MarqueeCore's PagedLoaderTests, with pages of 8 titles: the reader is "near the end" in the last 6. */
class PagedLoaderTest {
	private class Title(val key: String)

	private fun page(range: IntRange, total: Int) = Page(range.map { Title("$it") }, total)

	private val kept = { loader: PagedLoader<Title> -> loader.state.value.items.map { it.key } }

	@Test
	fun theNextPageStartsThreeQuartersOfAPageFromTheEnd() = runBlocking {
		val requests = mutableListOf<Pair<Int, Int>>()
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		loader.reload { start, size ->
			requests += start to size
			page(start until minOf(start + size, 20), 20)
		}

		loader.loadMoreIfNeeded("1")
		assertEquals(listOf(0 to 8), requests)

		loader.loadMoreIfNeeded("2")
		assertEquals(listOf(0 to 8, 8 to 8), requests)
		assertEquals(16, loader.state.value.items.size)
	}

	@Test
	fun noPageIsRequestedPastTheEnd() = runBlocking {
		var requests = 0
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		loader.reload { start, size ->
			requests++
			page(start until minOf(start + size, 10), 10)
		}
		loader.loadMoreIfNeeded("7")
		loader.loadMoreIfNeeded("9")

		assertEquals(2, requests)
		assertEquals(10, loader.state.value.items.size)
	}

	@Test
	fun aSlowAnswerForAnEarlierQueryIsDropped() = runBlocking {
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		val slow = CompletableDeferred<Page<Title>>()
		val first = launch(start = CoroutineStart.UNDISPATCHED) { loader.reload { _, _ -> slow.await() } }

		loader.reload { _, _ -> page(100..102, 3) }
		slow.complete(page(0..2, 3))
		first.join()

		assertEquals(listOf("100", "101", "102"), kept(loader))
		assertFalse(loader.state.value.isLoading)
	}

	@Test
	fun pagesThatOverlapDoNotRepeatTitles() = runBlocking {
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		loader.reload { start, _ -> if (start == 0) page(0..7, 30) else page(6..13, 30) }

		loader.loadMoreIfNeeded("7")

		assertEquals((0..13).map { "$it" }, kept(loader))
		// The next page still starts where the server's last one ended.
		val starts = mutableListOf<Int>()
		loader.reload { start, _ ->
			starts += start
			page(start until start + 8, 30)
		}
		loader.loadMoreIfNeeded("7")
		assertEquals(listOf(0, 8), starts)
	}

	@Test
	fun aJumpWaitsForThePageOnItsWayThenLoadsAsFarAsTheTitle() = runBlocking {
		val requests = mutableListOf<Pair<Int, Int>>()
		val gate = CompletableDeferred<Unit>()
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		loader.reload { start, size ->
			requests += start to size
			if (start == 8) gate.await()
			page(start until minOf(start + size, 100), 100)
		}
		val onItsWay = launch(start = CoroutineStart.UNDISPATCHED) { loader.loadMoreIfNeeded("7") }
		val jump = launch(start = CoroutineStart.UNDISPATCHED) { loader.ensureLoaded(through = 30) }
		yield()
		assertEquals(listOf(0 to 8, 8 to 8), requests)
		gate.complete(Unit)
		onItsWay.join()
		jump.join()

		assertEquals(listOf(0 to 8, 8 to 8, 16 to 22), requests)
		assertTrue(loader.state.value.items.size > 30)
	}

	@Test
	fun aRefreshThatFailsKeepsTheTitlesAndSaysWhy() = runBlocking {
		var calls = 0
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })
		loader.reload { _, _ ->
			if (++calls > 1) error("offline")
			page(0..7, 8)
		}

		loader.refresh()

		assertEquals((0..7).map { "$it" }, kept(loader))
		assertNotNull(loader.state.value.failure)
		assertFalse(loader.state.value.isLoading)
		assertNotNull(loader.state.value.loadedAt)
	}

	@Test
	fun aFirstPageThatFailsLeavesNothingAndSaysWhy() = runBlocking {
		val loader = PagedLoader<Title>(this, pageSize = 8, keyOf = { it.key })

		loader.reload { _, _ -> error("offline") }

		assertTrue(loader.state.value.items.isEmpty())
		assertNotNull(loader.state.value.failure)
		assertNull(loader.state.value.total)
		assertFalse(loader.state.value.isLoading)
	}
}

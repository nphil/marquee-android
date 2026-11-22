package com.nphil.marquee.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrPagerTest {
	private fun title(n: Int) = SeerrTitle(id = "movie/$n", kind = SeerrKind.Movie, tmdbId = n, name = "T$n")

	private fun page(number: Int, ids: IntRange, total: Int) = SeerrPage(ids.map(::title), number, total)

	private val SeerrPager.ids get() = state.value.items.map { it.tmdbId }

	@Test
	fun theNextPageStartsWithinTheLastNineTitles() = runBlocking {
		val asked = mutableListOf<Int>()
		val pager = SeerrPager()
		pager.reload { n ->
			asked += n
			page(n, (n * 20 - 19)..(n * 20), total = 3)
		}
		pager.loadMoreIfNeeded("movie/11")
		assertEquals(listOf(1), asked)
		pager.loadMoreIfNeeded("movie/12")
		assertEquals(listOf(1, 2), asked)
		assertEquals(40, pager.state.value.items.size)
	}

	@Test
	fun titlesAlreadyShownAreSkippedWhenPagesOverlap() = runBlocking {
		val pager = SeerrPager()
		pager.reload { n -> if (n == 1) page(1, 1..3, total = 2) else page(2, 3..5, total = 2) }
		pager.loadMore()
		assertEquals(listOf(1, 2, 3, 4, 5), pager.ids)
		assertFalse(pager.state.value.hasMore)
	}

	@Test
	fun aPageOfOnlySeenTitlesAsksForTheNext() = runBlocking {
		val asked = mutableListOf<Int>()
		val pager = SeerrPager()
		pager.reload { n ->
			asked += n
			when (n) {
				1 -> page(1, 1..3, total = 3)
				2 -> page(2, 1..3, total = 3)
				else -> page(3, 4..6, total = 3)
			}
		}
		pager.loadMore()
		assertEquals(listOf(1, 2, 3), asked)
		assertEquals(listOf(1, 2, 3, 4, 5, 6), pager.ids)
	}

	@Test
	fun aLaterFailureKeepsTheTitlesAndRetries() = runBlocking {
		var failing = true
		val pager = SeerrPager()
		pager.reload { n ->
			if (n == 2 && failing) error("down")
			page(n, (n * 3 - 2)..(n * 3), total = 2)
		}
		pager.loadMore()
		assertTrue(pager.state.value.moreFailed)
		assertEquals(3, pager.ids.size)

		failing = false
		pager.loadMore()
		assertFalse(pager.state.value.moreFailed)
		assertEquals(6, pager.ids.size)
	}

	@Test
	fun theFirstFailureIsKeptAndRefreshLoadsAgain() = runBlocking {
		var failing = true
		val pager = SeerrPager()
		pager.reload { if (failing) error("down") else page(1, 1..2, total = 1) }
		assertNotNull(pager.state.value.failure)
		assertFalse(pager.state.value.hasLoaded)

		failing = false
		pager.refresh()
		assertEquals(listOf(1, 2), pager.ids)
		assertTrue(pager.state.value.hasLoaded)
	}

	@Test
	fun aPageOfAnOlderReloadIsDropped() = runBlocking {
		val slow = CompletableDeferred<SeerrPage>()
		val pager = SeerrPager()
		val first = launch(start = CoroutineStart.UNDISPATCHED) { pager.reload { slow.await() } }
		pager.reload { page(1, 7..8, total = 1) }
		slow.complete(page(1, 1..2, total = 1))
		first.join()
		assertEquals(listOf(7, 8), pager.ids)
		assertFalse(pager.state.value.isLoading)
	}

	@Test
	fun refreshLoadedLoadsEveryPageShownAndKeepsTheLength() = runBlocking {
		val asked = mutableListOf<Int>()
		val pager = SeerrPager()
		pager.reload { n -> page(n, (n * 2 - 1)..(n * 2), total = 3).also { asked += n } }
		pager.loadMore()
		asked.clear()
		pager.refreshLoaded()
		assertEquals(listOf(1, 2), asked)
		assertEquals(listOf(1, 2, 3, 4), pager.ids)
	}
}

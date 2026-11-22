package com.nphil.marquee.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
	@Test
	fun theNewestSearchLeadsAndAnEarlierCopyOfItInAnyCaseGoes() {
		assertEquals(listOf("Alien", "dune", "heat"), recentSearchesWith(listOf("dune", "alien", "heat"), "  Alien "))
	}

	@Test
	fun onlyTheLastFourAreKept() {
		assertEquals(listOf("e", "d", "c", "b"), recentSearchesWith(listOf("d", "c", "b", "a"), "e"))
	}

	@Test
	fun aBlankSearchChangesNothing() {
		assertEquals(listOf("a"), recentSearchesWith(listOf("a"), "   "))
	}
}

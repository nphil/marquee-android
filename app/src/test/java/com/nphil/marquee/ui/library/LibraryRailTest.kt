package com.nphil.marquee.ui.library

import com.nphil.marquee.core.AlphabetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryRailTest {
	private val alphabet = ('A'..'Z').mapIndexed { index, letter -> AlphabetEntry(letter.toString(), 3, index * 3) }

	@Test
	fun everyLetterShowsWhenTheyFit() {
		assertSame(alphabet, railLetters(alphabet, 26))
		assertSame(alphabet, railLetters(alphabet, 40))
	}

	@Test
	fun aShortRailSamplesEvenlyAndKeepsTheFirstAndLastLetter() {
		val shown = railLetters(alphabet, 20)
		assertEquals(20, shown.size)
		assertEquals("A", shown.first().character)
		assertEquals("Z", shown.last().character)
		assertEquals(shown.map { it.character }.distinct(), shown.map { it.character })
	}

	@Test
	fun theRailNeverShowsFewerThanTwoLetters() {
		assertEquals(2, railFitting(10f, 16f))
		assertEquals(listOf("A", "Z"), railLetters(alphabet, 0).map { it.character })
		assertEquals(20, railFitting(329f, 16f))
	}

	@Test
	fun aTouchPicksTheLetterUnderItAcrossTheWholeAlphabet() {
		// 20 rows of 16 px centred in 400 px: the band runs from 40 to 360.
		assertEquals(0, railEntryIndex(y = 0f, height = 400f, shownCount = 20, rowHeight = 16f, entryCount = 26))
		assertEquals(0, railEntryIndex(y = 40f, height = 400f, shownCount = 20, rowHeight = 16f, entryCount = 26))
		assertEquals(13, railEntryIndex(y = 200f, height = 400f, shownCount = 20, rowHeight = 16f, entryCount = 26))
		assertEquals(25, railEntryIndex(y = 360f, height = 400f, shownCount = 20, rowHeight = 16f, entryCount = 26))
		assertEquals(25, railEntryIndex(y = 900f, height = 400f, shownCount = 20, rowHeight = 16f, entryCount = 26))
	}
}

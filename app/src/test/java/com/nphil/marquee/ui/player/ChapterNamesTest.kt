package com.nphil.marquee.ui.player

import com.nphil.marquee.core.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNamesTest {
	private val chapters = listOf(
		Chapter("Opening", 0, 60_000),
		Chapter(null, 60_000, 120_000),
		Chapter("  ", 120_000, 180_000),
	)

	@Test
	fun aChapterStartsAtItsStartAndRunsToTheNext() {
		assertEquals(0, chapterAt(chapters, 0))
		assertEquals(0, chapterAt(chapters, 59_999))
		assertEquals(1, chapterAt(chapters, 60_000))
		assertEquals(2, chapterAt(chapters, 500_000))
	}

	@Test
	fun beforeTheFirstChapterThereIsNone() {
		assertEquals(-1, chapterAt(listOf(Chapter("Late", 5_000, 9_000)), 1_000))
	}

	@Test
	fun anUnnamedChapterIsNumberedFromOne() {
		assertEquals("Opening", chapterName(chapters[0], 0))
		assertEquals("Chapter 2", chapterName(chapters[1], 1))
		assertEquals("Chapter 3", chapterName(chapters[2], 2))
	}

	@Test
	fun thePreviewNamesTheChapterOnlyWhenThereAreTwoOrMore() {
		assertEquals("Chapter 2", previewChapter(chapters, 61_000))
		assertNull(previewChapter(chapters.take(1), 1_000))
		assertNull(previewChapter(emptyList(), 1_000))
	}

	@Test
	fun thePreviewNamesNothingBeforeTheFirstChapter() {
		assertNull(previewChapter(listOf(Chapter("A", 5_000, 9_000), Chapter("B", 9_000, 12_000)), 1_000))
	}
}

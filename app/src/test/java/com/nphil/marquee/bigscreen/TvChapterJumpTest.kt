package com.nphil.marquee.bigscreen

import com.nphil.marquee.core.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvChapterJumpTest {
    private val chapters = listOf(Chapter(start = 0, end = 60_000), Chapter(start = 60_000, end = 90_000), Chapter(start = 90_000, end = 120_000))

    @Test fun upMovesForwardEvenWhenTheDecoderLandsJustBeforeAChapter() {
        assertEquals(90_000, chapterJump(chapters, 59_850, true))
        assertNull(chapterJump(chapters, 94_000, true))
    }

    @Test fun downRestartsTheChapterUnlessAlreadyAtItsBeginning() {
        assertEquals(60_000, chapterJump(chapters, 65_000, false))
        assertEquals(0, chapterJump(chapters, 61_000, false))
        assertNull(chapterJump(chapters.take(1), 12_000, false))
    }
}

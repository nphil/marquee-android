package com.nphil.marquee.ui.player

import com.nphil.marquee.core.Chapter

/** The position of the chapter [ms] falls in: the last one that starts at or before it; -1 before the first. */
internal fun chapterAt(chapters: List<Chapter>, ms: Long): Int = chapters.indexOfLast { it.start <= ms }

/** A chapter's name, or "Chapter 3" for one the file leaves unnamed. [index] counts from 0. */
internal fun chapterName(chapter: Chapter, index: Int): String = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"

/**
 * The chapter name to show over the seek preview at [ms], or null: a title with fewer than two chapters has nothing to
 * tell one stretch from another, the same rule that leaves the bar uncut.
 */
internal fun previewChapter(chapters: List<Chapter>, ms: Long): String? {
	if (chapters.size < 2) return null
	val at = chapterAt(chapters, ms)
	return if (at < 0) null else chapterName(chapters[at], at)
}

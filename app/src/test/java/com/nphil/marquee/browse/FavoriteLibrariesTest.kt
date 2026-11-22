package com.nphil.marquee.browse

import com.nphil.marquee.core.LibrarySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FavoriteLibrariesTest {
	private val movies = LibrarySection(key = "1", title = "Movies", type = "movie", id = "uuid-movies")
	private val shows = LibrarySection(key = "2", title = "TV", type = "show")
	private val music = LibrarySection(key = "3", title = "Music", type = "artist")
	private val sections = listOf(movies, shows, music)

	@Test
	fun onlyMovieAndShowLibrariesAreListedAndAHiddenOneIsOff() {
		val rows = favoriteLibraries(sections, hidden = setOf("2"))

		assertEquals(listOf(movies, shows), rows.map { it.section })
		assertEquals(listOf(true, false), rows.map { it.on })
	}

	@Test
	fun theLastLibraryOnIsTheOneThatCannotBeSwitchedOff() {
		assertFalse(favoriteLibraries(sections, hidden = emptySet()).any { it.isLast })
		assertEquals(listOf(true, false), favoriteLibraries(sections, hidden = setOf("2")).map { it.isLast })
		// None on, for instance after a library went away: nothing is last, so one can come back.
		assertFalse(favoriteLibraries(sections, hidden = setOf("uuid-movies", "2")).any { it.isLast })
	}

	@Test
	fun switchingOffAndOnIsKeptByTheLibrariesId() {
		val off = withLibrary(emptySet(), sections, movies, on = false)

		assertEquals(setOf("uuid-movies"), off)
		assertEquals(emptySet<String>(), withLibrary(off, sections, movies, on = true))
	}

	@Test
	fun theLastLibraryOnStaysOn() {
		val hidden = setOf("uuid-movies")

		assertEquals(hidden, withLibrary(hidden, sections, shows, on = false))
		assertEquals(setOf("uuid-movies"), withLibrary(setOf("uuid-movies", "2"), sections, shows, on = true))
	}
}

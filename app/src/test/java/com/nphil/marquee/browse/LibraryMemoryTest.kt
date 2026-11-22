package com.nphil.marquee.browse

import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.SectionQuery.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMemoryTest {
	@Test
	fun everySortOrderAndFilterComesBackAsItWentIn() {
		val genres = listOf(
			null,
			PlexDirectory(key = "12", title = "Drama"),
			PlexDirectory(key = "/genre/\"x\"", title = "Sci-Fi \"Classic\" – Ünï/cödé ✓"),
		)
		for (sort in Sort.entries) for (reversed in listOf(false, true)) for (unwatched in listOf(false, true)) for (genre in genres) {
			val options = LibraryOptions(sort = sort, reversed = reversed && sort.orders != null, unwatchedOnly = unwatched, genre = genre)
			assertEquals(options, decodeLibraryView(encodeLibraryView(options)))
		}
	}

	@Test
	fun theDefaultsAreNotWrittenAndWhichViewShowsIsNotKept() {
		assertNull(encodeLibraryView(LibraryOptions()))
		assertNull(encodeLibraryView(LibraryOptions(segment = LibrarySegment.Recommended)))
		assertEquals(LibraryOptions(), decodeLibraryView(null))
	}

	@Test
	fun theTextIsVersionedAndNamesOnlyWhatDiffers() {
		assertEquals(
			"""{"v":1,"sort":"addedAt","reversed":true}""",
			encodeLibraryView(LibraryOptions(sort = Sort.RecentlyAdded, reversed = true)),
		)
		assertEquals("""{"v":1,"sort":"titleSort","unwatched":true}""", encodeLibraryView(LibraryOptions(unwatchedOnly = true)))
	}

	@Test
	fun textThatIsMissingDamagedOrFromAnotherVersionReadsAsTheDefaults() {
		val unreadable = listOf(
			null, "", "  ", "not json", "{", "[]", "{}", "42",
			"""{"v":2,"sort":"addedAt"}""",
			"""{"v":0,"sort":"addedAt"}""",
			"""{"sort":"addedAt"}""",
			"""{"v":1}""",
			"""{"v":1,"sort":"nonsense"}""",
			"""{"v":1,"sort":"addedAt","genre":{"key":"","title":"Drama"}}""",
		)
		for (text in unreadable) assertEquals(text, LibraryOptions(), decodeLibraryView(text))
	}

	@Test
	fun randomHasNoDirectionSoItNeverComesBackReversed() {
		val options = decodeLibraryView("""{"v":1,"sort":"random","reversed":true}""")
		assertEquals(Sort.Random, options.sort)
		assertFalse(options.reversed)
	}

	private val sections = listOf(
		LibrarySection("1", "Movies", "movie"),
		LibrarySection("9", "Music", "artist"),
		LibrarySection("2", "Shows", "show", id = "uuid-2"),
	)

	@Test
	fun onlyMovieAndShowLibrariesAreOffered() {
		assertEquals(listOf("1", "2"), browsableLibraries(sections, emptySet()).map { it.key })
	}

	@Test
	fun aLibraryIsSwitchedOffByItsIdOrByItsKeyWhenItHasNone() {
		assertEquals(listOf("2"), browsableLibraries(sections, setOf("1")).map { it.key })
		assertEquals(listOf("1"), browsableLibraries(sections, setOf("uuid-2")).map { it.key })
		// The id names the library, not its key.
		assertEquals(listOf("1", "2"), browsableLibraries(sections, setOf("2")).map { it.key })
	}

	@Test
	fun switchingEveryLibraryOffOffersThemAll() {
		assertEquals(listOf("1", "2"), browsableLibraries(sections, setOf("1", "uuid-2")).map { it.key })
	}
}

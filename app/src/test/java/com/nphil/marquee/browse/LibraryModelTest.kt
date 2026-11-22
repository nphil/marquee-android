package com.nphil.marquee.browse

import com.nphil.marquee.core.AlphabetEntry
import com.nphil.marquee.core.Changes
import com.nphil.marquee.core.DeletedTitles
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.Page
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.core.SectionQuery.Sort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class LibraryModelTest {
	private val clock = DayClock(LocalDate.of(2024, 3, 8), ZoneOffset.UTC, DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))

	private fun at(date: String, time: String = "12:00:00") = LocalDateTime.parse("${date}T$time").toEpochSecond(ZoneOffset.UTC)

	@Test
	fun datesAreWordedAgainstToday() {
		assertEquals("Today", clock.day(at("2024-03-08")))
		assertEquals("Yesterday", clock.day(at("2024-03-07", "23:30:00")))
		assertEquals("Mar 1, 2024", clock.day(at("2024-03-01")))
		assertEquals("Yesterday", clock.releaseDate("2024-03-07"))
		assertEquals("Jan 2, 2025", clock.releaseDate("2025-01-02"))
		assertNull(clock.releaseDate(null))
		assertNull(clock.releaseDate("soon"))
		assertNull(clock.releaseDate("2024-13-40"))
	}

	@Test
	fun theSortValueIsWhatTheGridIsOrderedOn() {
		val unseen = PlexItem(ratingKey = "1")
		assertNull(unseen.sortValue(Sort.Title, clock))
		assertNull(unseen.sortValue(Sort.Random, clock))
		assertFalse(Sort.Title.showsValue)
		assertTrue(Sort.Plays.showsValue)

		assertNull(unseen.sortValue(Sort.RecentlyAdded, clock))
		assertEquals(SortValue("Today"), PlexItem(ratingKey = "2", addedAt = at("2024-03-08")).sortValue(Sort.RecentlyAdded, clock))
		assertNull(unseen.sortValue(Sort.Rating, clock))

		assertEquals(SortValue("Never", quiet = true), unseen.sortValue(Sort.RecentlyWatched, clock))
		assertEquals(SortValue("Mar 1, 2024"), PlexItem(ratingKey = "3", lastViewedAt = at("2024-03-01")).sortValue(Sort.RecentlyWatched, clock))

		assertEquals(SortValue("Never", quiet = true), unseen.sortValue(Sort.Plays, clock))
		assertEquals(SortValue("1\u00A0play"), PlexItem(ratingKey = "4", viewCount = 1).sortValue(Sort.Plays, clock))
		assertEquals(SortValue("14\u00A0plays"), PlexItem(ratingKey = "5", viewCount = 14).sortValue(Sort.Plays, clock))
	}

	@Test
	fun aNewFieldStartsInItsFirstOrderAndChipsFollowTheFilters() {
		val genre = PlexDirectory(key = "g", title = "Drama")
		val options = LibraryOptions().copy(reversed = true, unwatchedOnly = true, genre = genre)
		assertFalse(options.sortedBy(Sort.RecentlyAdded).reversed)
		assertEquals(listOf(LibraryChip.Unwatched, LibraryChip.Genre(genre)), options.chips)
		assertEquals(listOf(LibraryChip.Genre(genre)), options.without(LibraryChip.Unwatched).chips)
		assertTrue(options.cleared().chips.isEmpty())
		assertFalse(options.cleared().isFiltered)
		assertTrue(options.copy(segment = LibrarySegment.Collections).chips.isEmpty())
	}

	@Test
	fun aLetterIsFoundAmongTheTitlesThatAreStillShown() {
		val items = listOf("a", "b", "c", "d").map { PlexItem(ratingKey = it) }
		val deleted = DeletedTitles(setOf("b"))
		assertEquals(2, shownIndexOf(items, 3, deleted))
		assertEquals(3, shownIndexOf(items, 3, DeletedTitles()))
		assertNull(shownIndexOf(items, 4, deleted))
	}

	@Test
	fun aShowMarkedWatchedHasNoUnwatchedEpisodes() {
		val show = PlexItem(ratingKey = "s", leafCount = 10, viewedLeafCount = 4, unwatchedLeafCount = 6)
		val watched = show.markedWatched(true)
		assertTrue(watched.isWatched)
		assertEquals(0, watched.unwatchedLeafCount)
		assertEquals(10, watched.viewedLeafCount)
		val unwatched = watched.markedWatched(false)
		assertFalse(unwatched.isWatched)
		assertEquals(10, unwatched.unwatchedLeafCount)
		assertEquals(0, unwatched.viewedLeafCount)
	}

	private class FakeLibrary : LibrarySource {
		val titleQueries = mutableListOf<SectionQuery>()
		val letterQueries = mutableListOf<SectionQuery>()
		val collectionPages = mutableListOf<String>()
		val refetched = mutableListOf<List<String>>()

		override suspend fun sections() = listOf(
			LibrarySection("1", "Movies", "movie"),
			LibrarySection("9", "Music", "artist"),
			LibrarySection("2", "Shows", "show"),
		)

		override suspend fun titles(section: String, query: SectionQuery, start: Int, size: Int): Page<PlexItem> {
			titleQueries += query
			return Page(listOf("a", "b", "c").map { PlexItem(ratingKey = it) }, 3)
		}

		override suspend fun letters(section: String, query: SectionQuery): List<AlphabetEntry> {
			letterQueries += query
			return listOf(AlphabetEntry("A", 2, 0), AlphabetEntry("B", 1, 2))
		}

		override suspend fun genres(section: String) = emptyList<PlexDirectory>()

		override suspend fun collections(section: String, start: Int, size: Int): Page<PlexItem> {
			collectionPages += section
			return Page(emptyList(), 0)
		}

		override suspend fun recommended(section: String) = emptyList<PlexHub>()

		override suspend fun refetch(ratingKeys: List<String>): List<PlexItem> {
			refetched += ratingKeys
			return ratingKeys.map { PlexItem(ratingKey = it, viewCount = 5) }
		}
	}

	@Test
	fun aLibraryLoadsItsTitlesAndStartsOverWhenAnotherIsChosen() {
		val source = FakeLibrary()
		val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
		val model = LibraryModel(source, MutableStateFlow(Changes()), scope)

		assertEquals(listOf("1", "2"), model.libraries.value.map { it.key })
		val movies = model.browser.value!!
		assertEquals("1", movies.section.key)
		assertEquals(3, movies.titles.value.items.size)
		assertTrue(movies.showsRail.value)

		// Another order reloads the titles; letters are for title order only, so the rail goes.
		movies.sortBy(Sort.RecentlyAdded)
		assertEquals(Sort.RecentlyAdded, source.titleQueries.last().sort)
		assertEquals(1, source.letterQueries.size)
		assertFalse(movies.showsRail.value)

		movies.select(LibrarySegment.Collections)
		assertEquals(listOf("1"), source.collectionPages)

		model.choose("2")
		val shows = model.browser.value!!
		assertEquals("2", shows.section.key)
		assertEquals(LibraryOptions(), shows.options.value)
		assertEquals(Sort.Title, source.titleQueries.last().sort)
		scope.cancel()
	}

	@Test
	fun aTitleChangedElsewhereIsAskedForAgainAndADeletedOneDropsOut() {
		val source = FakeLibrary()
		val changes = MutableStateFlow(Changes())
		val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
		val movies = LibraryModel(source, changes, scope).browser.value!!

		changes.value = Changes(revision = 1, touched = mapOf("a" to 1, "elsewhere" to 1))
		assertEquals(listOf(listOf("a")), source.refetched)
		assertEquals(5, movies.titles.value.items.first { it.ratingKey == "a" }.viewCount)

		changes.value = Changes(revision = 2, deleted = DeletedTitles(setOf("b")))
		assertEquals(listOf("a", "c"), movies.titles.value.items.map { it.ratingKey })
		scope.cancel()
	}

	@Test
	fun theChosenLibraryIsTheOneAskedFor() {
		val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
		val model = LibraryModel(FakeLibrary(), MutableStateFlow(Changes()), scope, initialChoice = "2")
		assertEquals("2", model.selected.value?.key)
		assertEquals("2", model.browser.value?.section?.key)
		scope.cancel()
	}
}

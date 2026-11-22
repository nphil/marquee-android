package com.nphil.marquee.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout rules of MarqueeCore's HomeLayoutTests, over the rows as the bridge sends them. */
class HomeLayoutTest {
	private val movies = RowLibrary(id = "lib-movies", key = "1", title = "Movies")

	/** A row as `home.load` sends it: a library hub's id carries its library's, and a rotating hub's the stable form. */
	private fun row(hub: String, library: RowLibrary? = null) = HomeRow(
		id = (library?.let { "${it.id}/" } ?: "") + HomeIds.stable(hub),
		title = hub,
		library = library,
		hub = PlexHub(identifier = hub, title = hub),
	)

	private fun rows() = listOf(
		row("home.continue"),
		row("home.movies.recent"),
		row("home.movies.recent"),
		row("movie.toprated.1", movies),
	)

	@Test
	fun theDefaultShowsHomeHubsInServerOrderAndHidesLibraryHubs() {
		val arranged = HomeLayout().arrange(rows())

		assertEquals(listOf("home.continue", "home.movies.recent", "lib-movies/movie.toprated.1"), arranged.map { it.id })
		assertEquals(listOf(true, true, false), arranged.map { it.shown })
	}

	@Test
	fun placedRowsLeadAndNewHubsFollowWithTheirDefault() {
		val layout = HomeLayout(
			listOf(
				Placement("lib-movies/movie.toprated.1", shown = true, library = "lib-movies"),
				Placement("home.continue", shown = false),
			),
		)

		val arranged = layout.arrange(rows())

		assertEquals(listOf("lib-movies/movie.toprated.1", "home.continue", "home.movies.recent"), arranged.map { it.id })
		assertEquals(listOf(true, false, true), arranged.map { it.shown })
		assertEquals(setOf("lib-movies"), layout.shownLibraries)
	}

	@Test
	fun savingKeepsAnAbsentRowAfterTheRowItFollowed() {
		val layout = HomeLayout(
			listOf(Placement("home.continue"), Placement("home.ondeck"), Placement("home.movies.recent")),
		)
		val arranged = layout.arrange(rows()).toMutableList()
		arranged[0] = arranged[1].also { arranged[1] = arranged[0] }
		arranged[2] = arranged[2].copy(shown = true)

		val saved = layout.saving(arranged)

		assertEquals(
			listOf("home.movies.recent", "home.continue", "home.ondeck", "lib-movies/movie.toprated.1"),
			saved.placements.map { it.id },
		)
		assertEquals(setOf("lib-movies"), saved.shownLibraries)
	}

	@Test
	fun rotatingHubsKeepOneIdForEveryGenrePersonAndAccount() {
		// The owner and a Home profile get a different genre or person in the same row.
		assertEquals("movie.genre.1", HomeIds.stable("movie.genre.1.3718"))
		assertEquals("movie.genre.1", HomeIds.stable("movie.genre.1.6"))
		assertEquals("movie.by.actor.or.director.1", HomeIds.stable("movie.by.actor.or.director.1.39036"))
		assertEquals("lib-movies/movie.genre.1", HomeIds.stable("lib-movies/movie.genre.1.32"))
		// Ids that already name one hub stay as the server sends them.
		for (id in listOf("home.continue", "home.ondeck", "movie.recentlyadded.1", "tv.toprated.2", "custom.collection.1.18315.18315")) {
			assertEquals(id, HomeIds.stable(id))
		}
	}

	@Test
	fun aHiddenGenreRowStaysHiddenWhenTheServerPicksAnotherGenre() {
		val arranged = HomeLayout().arrange(listOf(row("home.continue"), row("movie.genre.1.3718"))).toMutableList()
		arranged[1] = arranged[1].copy(shown = false)
		val layout = HomeLayout().saving(arranged)

		val later = layout.arrange(listOf(row("home.continue"), row("movie.genre.1.6")))

		assertEquals(listOf(true, false), later.map { it.shown })
	}

	@Test
	fun aLayoutSavedByAnOlderVersionStillHidesItsRows() {
		// No `library` or `shown` keys, a raw rotating id, a repeat and an entry that is not a placement.
		val json = """
			{"placements":[{"id":"movie.genre.1.3718","shown":false},{"id":"home.ondeck"},7,
			{"id":"home.continue","shown":true,"future":1},{"id":"movie.genre.1.6","shown":true}]}
		""".trimIndent()

		val layout = HomeLayout.decode(json)

		assertEquals(listOf("movie.genre.1", "home.ondeck", "home.continue"), layout.placements.map { it.id })
		assertEquals(listOf(false, true, true), layout.placements.map { it.shown })
		val arranged = layout.arrange(listOf(row("home.continue"), row("movie.genre.1.32")))
		assertEquals(listOf(false, true), arranged.map { it.shown })
	}

	@Test
	fun hidingRowsOfTheOwnersHomeSurvivesAnEncodeAndDecode() {
		val owner = listOf(
			"home.continue", "home.ondeck", "movie.recentlyadded.1", "movie.genre.1.3718", "custom.collection.1.18315.18315",
			"custom.collection.1.18304.18304", "custom.collection.2.18346.18346", "tv.recentlyadded.2", "home.playlists",
		)
		val arranged = HomeLayout().arrange(owner.map { row(it) }).toMutableList()
		for (index in listOf(1, 3, 5)) arranged[index] = arranged[index].copy(shown = false)
		val layout = HomeLayout().saving(arranged)

		val reloaded = HomeLayout.decode(layout.encode())
		val shown = reloaded.arrange(owner.map { row(it) }).filter { it.shown }.map { it.id }

		assertEquals(owner.size - 3, shown.size)
		assertFalse("home.ondeck" in shown || "movie.genre.1" in shown || "custom.collection.1.18304.18304" in shown)
	}

	@Test
	fun noLayoutOrAnUnreadableOneIsTheServersOrder() {
		assertTrue(HomeLayout.decode(null).isDefault)
		assertTrue(HomeLayout.decode("{not json").isDefault)
		assertTrue(HomeLayout.decode("""{"placements":"none"}""").isDefault)
	}
}

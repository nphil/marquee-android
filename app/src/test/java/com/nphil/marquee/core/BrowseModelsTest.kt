package com.nphil.marquee.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseModelsTest {
	@Test
	fun aQueryReachesTheBridgeAsTheServerSpellsIt() {
		val query = SectionQuery(
			MediaType.Show, SectionQuery.Sort.RecentlyAdded,
			reversed = true, unwatchedOnly = true, genre = PlexDirectory(key = "27", title = "Comedy"),
		)

		assertEquals(
			"""{"type":"show","sort":"addedAt","reversed":true,"unwatchedOnly":true,"genre":{"key":"27","title":"Comedy"}}""",
			query.toJson().toString(),
		)
		assertEquals("""{"type":"movie","sort":"titleSort"}""", SectionQuery(MediaType.Movie).toJson().toString())
	}

	@Test
	fun onlyTheAlphabeticalOrderHasLetters() {
		assertTrue(SectionQuery(MediaType.Movie).isAlphabetical)
		assertFalse(SectionQuery(MediaType.Movie, reversed = true).isAlphabetical)
		assertFalse(SectionQuery(MediaType.Movie, SectionQuery.Sort.Random).isAlphabetical)
	}

	@Test
	fun rowsReadAsTheBridgeSendsThemAndAHubOfALibraryStartsHidden() {
		val reply = CoreJson.decodeFromString<HomeReply>(
			"""
			{"late":true,"run":"r1","savedAt":1700000000.5,"rows":[
				{"id":"home.continue","title":"Continue Watching","continueWatching":true,
				 "hub":{"identifier":"home.continue","title":"Continue Watching","style":"hero","items":[{"ratingKey":"1"}]}},
				{"id":"lib-movies/movie.toprated.1","title":"Top Rated","library":{"id":"lib-movies","key":"1","title":"Movies"},
				 "continueWatching":false,"hub":{"identifier":"movie.toprated.1","title":"Top Rated"}}
			]}
			""".trimIndent(),
		)

		assertTrue(reply.late)
		assertEquals("r1", reply.run)
		assertEquals(listOf(true, false), reply.rows.map { it.shown })
		assertTrue(reply.rows[0].continueWatching)
		assertEquals("1", reply.rows[0].hub.items.single().ratingKey)
		assertEquals("Movies", reply.rows[1].library?.title)
		assertNull(CoreJson.decodeFromString<HomeReply>("""{"rows":[]}""").savedAt)
	}
}

package com.nphil.marquee.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrWireTest {
	@Test
	fun aReplyCarriesASessionOnlyAfterASignIn() {
		val serializer = SeerrReply.serializer(SeerrUser.serializer())
		val with = CoreJson.decodeFromString(serializer, """{"value":{"id":1,"name":"Jordan"},"session":"connect.sid=abc"}""")
		assertEquals(SeerrUser(1, "Jordan"), with.value)
		assertEquals("connect.sid=abc", with.session)

		val without = CoreJson.decodeFromString(serializer, """{"value":{"id":1,"name":"Jordan"}}""")
		assertNull(without.session)
	}

	@Test
	fun aFeedKeepsItsQueryAcrossTheBridge() {
		val feed = SeerrFeed(query = SeerrBrowseQuery(SeerrKind.Tv, genre = 18, upcoming = true, streaming = SeerrStreaming(8, "GB")))
		val json = CoreJson.encodeToString(SeerrFeed.serializer(), feed)
		assertTrue(json, json.contains("\"tv\""))
		assertEquals(feed, CoreJson.decodeFromString(SeerrFeed.serializer(), json))
	}

	@Test
	fun aTitleDecodesWithTheBadgeCoreChose() {
		val json = """{"id":"movie/603","kind":"movie","tmdbID":603,"name":"The Matrix","year":1999,"badge":{"name":"available","title":"Available"}}"""
		val title = CoreJson.decodeFromString(SeerrTitle.serializer(), json)
		assertEquals(603, title.tmdbId)
		assertEquals("Available", title.badge?.title)
		assertNull(title.posterPath)
	}
}

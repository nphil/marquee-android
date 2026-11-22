package com.nphil.marquee.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelsTest {
	/** An episode as the bridge sends it (ItemWire.swift): Plex's field names, absent fields left out. */
	private val episode = CoreJson.decodeFromString<PlexItem>(
		"""
		{
			"ratingKey": "212", "key": "/library/metadata/212", "type": "episode", "title": "Gran Dillama",
			"index": 2, "parentIndex": 1, "duration": 2400000, "viewOffset": 600000,
			"grandparentTitle": "Caminandes", "grandparentThumb": "/library/metadata/200/thumb/1",
			"originallyAvailableAt": "2013-05-05", "futureField": {"ignored": true},
			"media": [{"id": 3, "videoResolution": "1080", "parts": [{
				"id": 4, "key": "/library/parts/4/1/file.mkv", "size": 850000000,
				"tracks": {
					"audio": [{"id": 9, "label": "English", "codec": "aac", "channels": 2, "isDefault": true, "badges": []}],
					"subtitles": [{"id": 10, "label": "English", "codec": "srt", "isExternal": true, "badges": ["Captions"]}]
				}
			}]}]
		}
		""".trimIndent(),
	)

	@Test
	fun anEpisodeMidwayKnowsItsProgressAndWhatIsLeft() {
		assertEquals(0.25f, episode.progress!!, 0.0001f)
		assertEquals(30, episode.minutesLeft)
		assertEquals("S1 · E2", episode.episodeCode)
		assertEquals("/library/metadata/200/thumb/1", episode.posterPath)
		assertEquals("Caminandes", episode.posterTitle)
		val meta = episode.metaLine!!
		assertTrue(meta, meta.startsWith("S1 · E2 · ") && meta.endsWith(" · 40 min"))
	}

	@Test
	fun tracksArriveNamedWithTheirFile() {
		val tracks = episode.media.single().parts.single().tracks!!
		assertEquals("English", tracks.audio.single().label)
		assertTrue(tracks.audio.single().isDefault)
		assertTrue(tracks.subtitles.single().isExternal)
		assertEquals(listOf("Captions"), tracks.subtitles.single().badges)
	}

	@Test
	fun anItemNotStartedOrFinishedHasNoProgress() {
		assertNull(episode.copy(viewOffset = 0).progress)
		assertNull(episode.copy(viewOffset = 2_400_000).progress)
		assertNull(episode.copy(viewOffset = 0).minutesLeft)
	}

	/**
	 * An On Deck or Continue Watching item as the bridge sends it. `isWatched` is Core's, where a view count alone makes
	 * an episode watched.
	 */
	private fun hubItem(json: String): PlexItem = CoreJson.decodeFromString(json)

	@Test
	fun anEpisodeWatchedAgainAndPartWayThroughHasNoCheck() {
		val rewatch = hubItem(
			"""{"ratingKey": "301", "type": "episode", "grandparentTitle": "The Office", "duration": 1320000,
				"viewOffset": 420000, "viewCount": 2, "isWatched": true}""",
		)
		assertFalse(rewatch.showsWatched)
		assertFalse(rewatch.spokenSummary, rewatch.spokenSummary.contains("watched"))
		assertEquals(15, rewatch.minutesLeft)
	}

	@Test
	fun aFinishedMovieOrEpisodeHasTheCheck() {
		assertTrue(hubItem("""{"ratingKey": "1", "type": "movie", "duration": 7200000, "viewCount": 1, "isWatched": true}""").showsWatched)
		assertTrue(episode.copy(viewOffset = 0, viewCount = 1, isWatched = true).showsWatched)
		assertTrue(episode.copy(viewOffset = 2_400_000, viewCount = 1, isWatched = true).showsWatched)
	}

	@Test
	fun anEpisodeNotWatchedHasNoCheck() {
		assertFalse(hubItem("""{"ratingKey": "2", "type": "episode", "duration": 1320000, "viewOffset": 420000}""").showsWatched)
		assertFalse(hubItem("""{"ratingKey": "3", "type": "episode", "duration": 1320000}""").showsWatched)
	}

	@Test
	fun aShowInProgressHasItsCountAndNoCheck() {
		val show = hubItem(
			"""{"ratingKey": "10", "type": "show", "leafCount": 224, "viewedLeafCount": 100, "unwatchedLeafCount": 124}""",
		)
		assertFalse(show.showsWatched)
		assertEquals(124, show.unwatchedBadge)
	}

	@Test
	fun aShowWithEveryEpisodeViewedHasTheCheckAndNoCount() {
		val show = hubItem(
			"""{"ratingKey": "10", "type": "show", "leafCount": 224, "viewedLeafCount": 224, "unwatchedLeafCount": 0, "isWatched": true}""",
		)
		assertTrue(show.showsWatched)
		assertNull(show.unwatchedBadge)
		assertTrue(show.copy(type = MediaType.Season).showsWatched)
	}

	@Test
	fun aShowListedWithoutItsEpisodeCountsIsNotCalledWatched() {
		val show = hubItem("""{"ratingKey": "10", "type": "show", "viewCount": 12, "isWatched": true}""")
		assertFalse(show.showsWatched)
		assertFalse(show.copy(type = MediaType.Season).showsWatched)
	}

	@Test
	fun aCollectionOrClipNeverHasTheCheck() {
		assertFalse(PlexItem(ratingKey = "20", type = MediaType.Collection, isWatched = true, leafCount = 3).showsWatched)
		assertFalse(PlexItem(ratingKey = "21", type = MediaType.Clip, isWatched = true, viewCount = 1).showsWatched)
	}

	@Test
	fun aVersionIsNamedByItsPictureAndSize() {
		val hdr = PlexStream(streamType = 1, colorTrc = "smpte2084")
		val version = PlexMedia(
			videoResolution = "4k",
			parts = listOf(PlexPart(size = 12_000_000_000, streams = listOf(hdr))),
		)
		assertEquals("4K HDR · 12 GB", version.versionLabel)
		assertEquals(listOf("4K", "HDR"), version.formatChips)
		assertEquals("MKV", PlexMedia(container = "mkv").versionLabel)
	}

	@Test
	fun runtimesAreWrittenToTheMinute() {
		assertEquals("42 min", ItemText.runtime(42 * 60_000))
		assertEquals("2 h 14 m", ItemText.runtime(134 * 60_000))
		assertEquals("2 h", ItemText.runtime(120 * 60_000))
		assertNull(ItemText.runtime(0))
		assertEquals("1 hour 12 minutes left", ItemText.spokenLeft(72))
	}

	@Test
	fun theWatchlistKnowsOnlyCatalogueGuids() {
		assertEquals("5d776b59ad5437001f79c6f8", Watchlist.catalogueKey("plex://movie/5d776b59ad5437001f79c6f8"))
		assertNull(Watchlist.catalogueKey("com.plexapp.agents.imdb://tt0000001?lang=en"))
		assertNull(Watchlist.catalogueKey(null))
	}

	@Test
	fun aDeletedSeasonTakesItsEpisodesOutOfEveryList() {
		val changes = LibraryChanges()
		val inSeason = episode.copy(parentRatingKey = "300")
		val elsewhere = episode.copy(ratingKey = "400", parentRatingKey = "301")
		val before = changes.state.value.revision
		changes.recordDeletion(PlexItem(ratingKey = "300", type = MediaType.Season))
		val after = changes.state.value
		assertEquals(listOf(elsewhere), after.shown(listOf(inSeason, elsewhere)))
		assertTrue("300" in after.keysAfter(before))
		changes.forgetDeletions()
		assertEquals(2, changes.state.value.shown(listOf(inSeason, elsewhere)).size)
	}

	@Test
	fun aTitleTakenOffTheWatchlistLeavesItAtOnce() {
		val changes = LibraryChanges()
		val listed = PlexItem(ratingKey = "1", guid = "plex://movie/abc")
		val other = PlexItem(ratingKey = "2", guid = "plex://movie/def")
		changes.hideFromWatchlist("plex://movie/abc")
		assertEquals(listOf(other), changes.state.value.shownInWatchlist(listOf(listed, other)))
		changes.showInWatchlist("plex://movie/abc")
		assertEquals(listOf(listed, other), changes.state.value.shownInWatchlist(listOf(listed, other)))
	}
}

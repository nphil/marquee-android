package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.AudioChoice
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.PlexStream
import com.nphil.marquee.core.SubtitleChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailModelTest {
	private fun episode(key: String, watched: Boolean) =
		PlexItem(ratingKey = key, type = MediaType.Episode, isWatched = watched, viewOffset = if (watched) 0 else 1_000, viewCount = if (watched) 1 else 0)

	private fun season(key: String, leaves: Int, unwatched: Int, index: Int = 1) = PlexItem(
		ratingKey = key, type = MediaType.Season, title = "Season $index", index = index,
		leafCount = leaves, unwatchedLeafCount = unwatched, viewedLeafCount = leaves - unwatched, isWatched = unwatched == 0,
	)

	/** A show of two seasons of three episodes; season 1 is listed, with its last episode unwatched. */
	private val show = DetailState(
		item = PlexItem(ratingKey = "1", type = MediaType.Show, leafCount = 6, unwatchedLeafCount = 4, viewedLeafCount = 2),
		seasons = listOf(season("10", 3, 1), season("20", 3, 3, index = 2)),
		seasonKey = "10",
		episodes = listOf(episode("11", true), episode("12", true), episode("13", false)),
	)

	@Test
	fun markingAShowTakesItsSeasonsAndEpisodesWithIt() {
		val marked = show.withWatched("1", true)
		assertTrue(marked.item!!.isWatched)
		assertEquals(0, marked.item!!.unwatchedLeafCount)
		assertTrue(marked.seasons.all { it.isWatched && it.unwatchedLeafCount == 0 })
		assertTrue(marked.episodes!!.all { it.isWatched })
		val unmarked = marked.withWatched("1", false)
		assertEquals(6, unmarked.item!!.unwatchedLeafCount)
		assertEquals(listOf(3, 3), unmarked.seasons.map { it.unwatchedLeafCount })
		assertTrue(unmarked.episodes!!.none { it.isWatched })
	}

	@Test
	fun markingAnEpisodeMovesTheCountsOfItsSeasonAndShow() {
		val marked = show.withWatched("13", true)
		assertTrue(marked.episodes!!.all { it.isWatched })
		assertEquals(0, marked.season!!.unwatchedLeafCount)
		assertTrue(marked.season!!.isWatched)
		assertEquals(3, marked.item!!.unwatchedLeafCount)
		val back = marked.withWatched("11", false)
		assertEquals(1, back.season!!.unwatchedLeafCount)
		assertEquals(4, back.item!!.unwatchedLeafCount)
		assertFalse(back.season!!.isWatched)
	}

	@Test
	fun markingASeasonLeavesTheOtherSeasonsAndTheListedEpisodesAlone() {
		val marked = show.withWatched("20", true)
		assertEquals(listOf(1, 0), marked.seasons.map { it.unwatchedLeafCount })
		assertEquals(1, marked.item!!.unwatchedLeafCount)
		assertEquals(show.episodes, marked.episodes)
		val listed = show.withWatched("10", true)
		assertTrue(listed.episodes!!.all { it.isWatched })
		assertEquals(3, listed.item!!.unwatchedLeafCount)
	}

	@Test
	fun markingAMovieUnwatchedClearsItsResumePoint() {
		val movie = DetailState(item = PlexItem(ratingKey = "5", type = MediaType.Movie, isWatched = true, viewCount = 2))
		val unmarked = movie.withWatched("5", false).item!!
		assertFalse(unmarked.isWatched)
		assertEquals(0, unmarked.viewCount)
		assertEquals(0, unmarked.viewOffset)
		assertEquals(1, movie.copy(item = unmarked).withWatched("5", true).item!!.viewCount)
	}

	@Test
	fun anUnknownKeyChangesNothing() {
		assertEquals(show, show.withWatched("999", true))
	}

	@Test
	fun theFirstSeasonIsTheOneWithTheEpisodeUpNext() {
		val specials = season("0", 2, 2, index = 0)
		val one = season("10", 3, 0)
		val two = season("20", 3, 2, index = 2)
		val three = season("30", 3, 3, index = 3)
		val seasons = listOf(specials, one, two, three)
		assertEquals("30", firstSeason(seasons, PlexItem(ratingKey = "x", parentRatingKey = "30"))?.ratingKey)
		assertEquals("20", firstSeason(seasons, null)?.ratingKey)
		assertEquals("10", firstSeason(listOf(specials, one, season("20", 3, 0, index = 2)), null)?.ratingKey)
		assertEquals("0", firstSeason(listOf(specials), null)?.ratingKey)
		assertNull(firstSeason(emptyList(), null))
	}

	@Test
	fun theTrailerIsTheOneTheServerNamesElseTheFirstListed() {
		val behind = PlexItem(ratingKey = "7", key = "/library/metadata/7", subtype = "behindTheScenes")
		val one = PlexItem(ratingKey = "8", key = "/library/metadata/8", subtype = "trailer")
		val two = PlexItem(ratingKey = "9", key = "/library/metadata/9", extraType = 1)
		val extras = listOf(behind, one, two)
		assertEquals("9", trailerOf(extras, "/library/metadata/9")?.ratingKey)
		assertEquals("8", trailerOf(extras, null)?.ratingKey)
		assertEquals("8", trailerOf(extras, "/library/metadata/404")?.ratingKey)
		assertNull(trailerOf(listOf(behind), null))
	}

	@Test
	fun tracksAreNamedByFormatAndMarkedForTheirSound() {
		val atmos = AudioChoice(id = 3, label = "English", codec = "truehd", channels = 8, badges = listOf("Audio description"))
		val pcm = AudioChoice(id = 4, label = "Commentary", codec = "pcm_s16le", channels = 2)
		val part = PlexPart(streams = listOf(PlexStream(id = 3, streamType = 2, extendedDisplayTitle = "English (TRUEHD Atmos 7.1)")))
		assertEquals("TRUEHD 7.1", audioDetail(atmos))
		assertEquals(listOf("Dolby", "Atmos", "Audio description"), audioChips(atmos, part))
		assertEquals(listOf("PCM"), audioChips(pcm, part))
		assertEquals("PCM_S16LE 2.0", audioDetail(pcm))
		assertEquals("5.1", channelLayout(6))
		assertEquals("4 ch", channelLayout(4))
	}

	@Test
	fun onlyPictureSubtitlesCarryAFormatMark() {
		val srt = SubtitleChoice(id = 1, label = "English", codec = "srt", isExternal = true)
		val pgs = SubtitleChoice(id = 2, label = "English (SDH)", codec = "pgs", imageFormat = "PGS", badges = listOf("Captions"))
		assertEquals("SRT · External", subtitleDetail(srt))
		assertTrue(subtitleChips(srt).isEmpty())
		assertEquals(listOf("PGS", "Captions"), subtitleChips(pgs))
	}

	@Test
	fun extrasAreNamedByPlexsOwnWordForThem() {
		assertEquals("Trailer", extraKind(PlexItem(ratingKey = "1", subtype = "trailer")))
		assertEquals("Behind the Scenes", extraKind(PlexItem(ratingKey = "1", extraType = 5)))
		assertEquals("Deleted Scene", extraKind(PlexItem(ratingKey = "1", subtype = "deletedScene", extraType = 99)))
		assertEquals("Extra", extraKind(PlexItem(ratingKey = "1", extraType = 12)))
	}

	@Test
	fun aSeasonSaysHowMuchIsLeftAndACastMemberHasInitials() {
		assertEquals("Season 2 · 3 unwatched", seasonLabel(season("20", 3, 3, index = 2)))
		assertEquals("Season 1", seasonLabel(season("10", 3, 0)))
		assertEquals("AB", initials("Ada Byron King"))
		assertEquals("M", initials("Madonna"))
	}
}

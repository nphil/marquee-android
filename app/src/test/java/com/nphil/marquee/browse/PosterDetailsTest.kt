package com.nphil.marquee.browse

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.PlexStream
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.data.PosterLabels
import com.nphil.marquee.data.PosterLabels.Details
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PosterDetailsTest {
	private val clock = DayClock(LocalDate.of(2025, 1, 1), ZoneOffset.UTC, DateTimeFormatter.ISO_LOCAL_DATE)
	private val movie = PlexItem("movie", type = MediaType.Movie, year = 2019, duration = 8_040_000)

	@Test
	fun aSortValueWinsButMissingOrDisabledSortUsesDetails() {
		val labels = PosterLabels(details = Details.YearAndLength)
		assertEquals(SortValue("Never", quiet = true), movie.posterLine(labels, Sort.Plays, clock))
		assertEquals("2019 · 2 h 14 m", movie.posterLine(labels, Sort.Rating, clock)?.text)
		assertEquals("2019 · 2 h 14 m", movie.posterLine(labels.copy(showsSortValue = false), Sort.Plays, clock)?.text)
		assertTrue(PosterLabels().reservesLine(Sort.Rating))
		assertFalse(PosterLabels().reservesLine(Sort.Random))
		assertFalse(PosterLabels(showsTitle = false, showsSortValue = false).reservesLine(Sort.Plays))
	}

	@Test
	fun detailsFitWhatThePosterRepresents() {
		val show = PlexItem("show", type = MediaType.Show, year = 2019, childCount = 6, viewedLeafCount = 4, unwatchedLeafCount = 1)
		val collection = PlexItem("collection", type = MediaType.Collection, childCount = 3)
		val video = PlexStream(streamType = 1, colorTrc = "smpte2084")
		val movieWithVideo = movie.copy(media = listOf(
			PlexMedia(videoResolution = "1080", parts = listOf(PlexPart(streams = listOf(video)))),
		))
		assertEquals("1 new episode", show.posterLine(PosterLabels(details = Details.YearAndLength), null, clock)?.text)
		assertEquals("2019", show.posterLine(PosterLabels(details = Details.Video), null, clock)?.text)
		assertEquals("3 titles", collection.posterLine(PosterLabels(details = Details.Genre), null, clock)?.text)
		assertEquals("1080p · HDR", movieWithVideo.posterLine(PosterLabels(details = Details.Video), null, clock)?.text)
		assertEquals("2025-04-19", movie.copy(originallyAvailableAt = "2025-04-19").posterLine(PosterLabels(details = Details.Genre), null, clock)?.text)
		assertEquals("1 min left", movie.copy(viewOffset = movie.duration - 60_000).posterLine(PosterLabels(details = Details.Genre), null, clock)?.text)
		assertNull(movie.posterLine(PosterLabels(), null, clock))
	}
}

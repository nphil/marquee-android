package com.nphil.marquee.playback

import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayRoute
import com.nphil.marquee.core.PlayStream
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.SubtitleSource
import com.nphil.marquee.core.subtitleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePlaybackTest {
	private val plan = PlayPlan(
		session = "playback",
		item = PlexItem(ratingKey = "7", media = listOf(
			PlexMedia(parts = listOf(PlexPart(id = 15))),
			PlexMedia(parts = listOf(PlexPart(id = 18))),
		)),
		mediaIndex = 1,
		route = PlayRoute(RouteKind.DirectPlay),
	)

	@Test
	fun onlineServicesUseThePlayingMediaVersionForFilesAndHls() {
		val expected = SubtitleSource("playback", "7", 18)
		val file = PlayStream(PlayStream.StreamKind.File, "https://plex.example.test/film.mkv")
		val hls = PlayStream(PlayStream.StreamKind.Hls, "http://plex.example.test/film.m3u8")
		assertEquals(expected, plan.copy(stream = file).subtitleSource())
		assertEquals(expected, plan.copy(stream = hls).subtitleSource())
	}

	@Test
	fun localFilesAndUnopenedTitlesHaveNoOnlineSubtitleSource() {
		assertNull(plan.subtitleSource())
		val local = PlayStream(PlayStream.StreamKind.File, "file:///downloads/film.mkv")
		assertNull(plan.copy(stream = local).subtitleSource())
	}

	@Test
	fun eachWordFadesFor120MillisecondsFromItsStart() {
		assertEquals(0, wordAlpha(449, 450.0))
		assertEquals(0, wordAlpha(450, 450.0))
		assertEquals(128, wordAlpha(510, 450.0))
		assertEquals(255, wordAlpha(570, 450.0))
	}
}

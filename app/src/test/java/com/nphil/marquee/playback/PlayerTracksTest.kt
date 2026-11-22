package com.nphil.marquee.playback

import com.nphil.marquee.core.DELIVERY_IN_STREAM
import com.nphil.marquee.core.DELIVERY_NOT_SHOWN
import com.nphil.marquee.core.Delivery
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayRoute
import com.nphil.marquee.core.PlayTracks
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.RouteKind
import com.nphil.marquee.core.SubtitleOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTracksTest {
	private val inside = SubtitleOption(3, "English (SRT)", "en", delivery = DELIVERY_IN_STREAM)
	private val picture = SubtitleOption(4, "English SDH (PGS)", "en", delivery = DELIVERY_NOT_SHOWN)

	private fun tracks() = PlayerTracks(
		CoroutineScope(Job()), OkHttpClient(), movieMs = { 0L }, core = MarqueeCore { error("No bridge calls") }, speechDock = flowOf(null),
	)

	private fun plan(subtitle: Delivery) = PlayPlan(
		session = "s",
		item = PlexItem(ratingKey = "1"),
		route = PlayRoute(RouteKind.DirectPlay, audio = 2, subtitle = subtitle),
		tracks = PlayTracks(subtitles = listOf(inside, picture)),
	)

	@Test
	fun aStreamOpensWithTheAudioAndSubtitleItsRouteCarries() {
		val tracks = tracks()
		tracks.begin(plan(Delivery(DELIVERY_IN_STREAM, 3)), isFile = true, online = null)
		assertEquals(2, tracks.audioId)
		assertEquals(3, tracks.subtitleId)
	}

	@Test
	fun aSubtitleTheTabletCannotDrawReadsAsOff() {
		val tracks = tracks()
		tracks.begin(plan(Delivery(DELIVERY_NOT_SHOWN, 4)), isFile = true, online = null)
		assertNull(tracks.subtitleId)
		tracks.show(picture, engine = null)
		assertNull(tracks.subtitleId)
	}

	@Test
	fun aRouteWithNoSubtitleReadsAsOff() {
		val tracks = tracks()
		tracks.begin(plan(Delivery()), isFile = false, online = null)
		assertNull(tracks.subtitleId)
	}

	@Test
	fun showingNothingTurnsTheSubtitleOffAndAPickTurnsItOn() {
		val tracks = tracks()
		tracks.begin(plan(Delivery(DELIVERY_IN_STREAM, 3)), isFile = true, online = null)
		tracks.show(null, engine = null)
		assertNull(tracks.subtitleId)
		tracks.show(inside, engine = null)
		assertEquals(3, tracks.subtitleId)
	}
}

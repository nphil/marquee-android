package com.nphil.marquee.playback

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.playback.ThemeMusic.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMusicTest {
	private fun item(type: MediaType, theme: String? = null, parentTheme: String? = null) =
		PlexItem(ratingKey = "1", type = type, theme = theme, parentTheme = parentTheme)

	@Test
	fun aShowPlaysItsTuneAndASeasonItsShows() {
		assertEquals("/t/1", ThemeMusic.key(item(MediaType.Show, theme = "/t/1", parentTheme = "/t/2")))
		assertEquals("/t/2", ThemeMusic.key(item(MediaType.Season, theme = "/t/1", parentTheme = "/t/2")))
	}

	@Test
	fun noTuneOrAnotherKindOfItemHasNoKey() {
		assertNull(ThemeMusic.key(item(MediaType.Show)))
		assertNull(ThemeMusic.key(item(MediaType.Show, theme = "")))
		assertNull(ThemeMusic.key(item(MediaType.Episode, theme = "/t/1", parentTheme = "/t/2")))
		assertNull(ThemeMusic.key(item(MediaType.Movie, theme = "/t/1")))
	}

	@Test
	fun playsOnlyWhenNothingIsInTheWay() {
		assertTrue(ThemeMusic.plays(Conditions()))
		val holds = listOf(
			Conditions(enabled = false),
			Conditions(appActive = false),
			Conditions(pageVisible = false),
			Conditions(playerOpen = true),
			Conditions(otherAudioPlaying = true),
			Conditions(trailerSounding = true),
		)
		holds.forEach { assertFalse(it.toString(), ThemeMusic.plays(it)) }
	}

	@Test
	fun fadesInOverOneAndAHalfSecondsAndVolumeFollowsTheSquare() {
		val fade = ThemeFade().apply { rising = true }
		fade.advance(0.75)
		assertEquals(0.5, fade.gain, 1e-9)
		assertEquals(0.25f, fade.volume(1.0), 1e-6f)
		assertEquals(0.125f, fade.volume(0.5), 1e-6f)
		assertFalse(fade.isSettled)
		fade.advance(5.0)
		assertTrue(fade.isSettled)
		assertEquals(0.5f, fade.volume(0.5), 1e-6f)
	}

	@Test
	fun fadesOutOverOneSecondFromWhereItWasAndSettlesAtSilence() {
		val fade = ThemeFade().apply { rising = true }
		fade.advance(0.75)
		fade.rising = false
		fade.advance(0.25)
		assertEquals(0.25, fade.gain, 1e-9)
		fade.advance(1.0)
		assertEquals(0f, fade.volume(1.0), 0f)
		assertTrue(fade.isSettled)
	}

	@Test
	fun aVolumeOutsideZeroToOneIsClamped() {
		val fade = ThemeFade().apply { rising = true }
		fade.advance(10.0)
		assertEquals(1f, fade.volume(3.0), 0f)
		assertEquals(0f, fade.volume(-1.0), 0f)
	}
}

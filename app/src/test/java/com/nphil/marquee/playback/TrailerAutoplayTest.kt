package com.nphil.marquee.playback

import com.nphil.marquee.playback.TrailerAutoplay.Conditions
import com.nphil.marquee.playback.TrailerAutoplay.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TrailerAutoplayTest {
	@Test
	fun playsWithSoundWhenNothingIsInTheWay() {
		assertEquals(Verdict.Play(muted = false), TrailerAutoplay.decide(Conditions()))
		assertEquals(2.seconds, TrailerAutoplay.dwell)
	}

	@Test
	fun eachConditionHoldsIt() {
		val holds = listOf(
			Conditions(enabled = false),
			Conditions(powerSaver = true),
			Conditions(reduceMotion = true),
			Conditions(metered = true),
			Conditions(appActive = false),
			Conditions(heroVisible = false),
			Conditions(playerOpen = true),
		)
		holds.forEach { assertEquals(it.toString(), Verdict.Hold, TrailerAutoplay.decide(it)) }
	}

	@Test
	fun otherAudioMutesRatherThanHolds() {
		assertEquals(Verdict.Play(muted = true), TrailerAutoplay.decide(Conditions(otherAudioPlaying = true)))
	}
}

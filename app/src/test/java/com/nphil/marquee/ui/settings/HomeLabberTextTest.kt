package com.nphil.marquee.ui.settings

import com.nphil.marquee.core.HomeLabberConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class HomeLabberTextTest {
	@Test
	fun theStatusSaysHowManyTitlesAreMeasuredAndWhenItLastRan() {
		val running = HomeLabberConnection(enabled = true, scored = 1_200, total = 2_390, running = true)
		val words = HomeLabberText.connection(running, ZoneOffset.UTC, Locale.US)
		assertTrue(words.startsWith("Connected · 1,200 of 2,390 titles measured, measuring now. "))
		assertTrue(words.endsWith("Auto's usual steps."))
		val idle = running.copy(running = false, lastRun = 1_763_000_000.0, encoder = "h264_vaapi")
		assertTrue(HomeLabberText.connection(idle, ZoneOffset.UTC, Locale.US).endsWith("Auto's usual steps. Last run Nov 13, 2025."))
		assertTrue(HomeLabberText.connection(HomeLabberConnection(enabled = false, scored = 0, total = 0)).contains("off in HomeLabber"))
	}

	@Test
	fun eachProblemHasItsOwnWords() {
		assertEquals("The address must start with https:// or http://.", HomeLabberText.address("scheme"))
		assertEquals("That is not a Marquee token. HomeLabber's start with hlmq_.", HomeLabberText.address("token"))
		assertEquals("That is not a valid address.", HomeLabberText.address("host"))
		assertTrue(HomeLabberText.problem("badToken").startsWith("HomeLabber refused the token."))
		assertTrue(HomeLabberText.problem("notHomeLabber").startsWith("That is not HomeLabber."))
		assertTrue(HomeLabberText.problem("timedOut").startsWith("Can't reach HomeLabber."))
	}
}

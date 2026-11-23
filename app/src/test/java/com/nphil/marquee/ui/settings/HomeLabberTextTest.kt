package com.nphil.marquee.ui.settings

import com.nphil.marquee.core.HomeLabberConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLabberTextTest {
	@Test
	fun theStatusSaysHowManyTitlesAreMeasured() {
		val running = HomeLabberConnection(enabled = true, scored = 1_200, total = 2_390, running = true)
		assertTrue(HomeLabberText.connection(running).contains("titles measured, measuring now."))
		assertTrue(HomeLabberText.connection(running.copy(running = false)).endsWith("titles measured. The others' download sizes are estimated."))
		assertTrue(HomeLabberText.connection(HomeLabberConnection(enabled = false, scored = 0, total = 0)).contains("off in HomeLabber"))
	}

	@Test
	fun eachProblemHasItsOwnWords() {
		assertEquals("The address must start with https:// or http://.", HomeLabberText.address("scheme"))
		assertEquals("That is not a valid token.", HomeLabberText.address("token"))
		assertEquals("That is not a valid address.", HomeLabberText.address("host"))
		assertTrue(HomeLabberText.problem("badToken").startsWith("HomeLabber refused the token."))
		assertTrue(HomeLabberText.problem("notHomeLabber").startsWith("That is not HomeLabber."))
		assertTrue(HomeLabberText.problem("timedOut").startsWith("Can't reach HomeLabber."))
	}
}

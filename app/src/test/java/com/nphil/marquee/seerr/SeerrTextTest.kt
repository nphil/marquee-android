package com.nphil.marquee.seerr

import com.nphil.marquee.core.CoreError
import com.nphil.marquee.core.CoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrTextTest {
	private fun seerr(failure: String, message: String? = null) = CoreException(CoreError("seerr", message = message, failure = failure))

	@Test
	fun aProblemShowsOnlyOnceTheFieldIsLeftAndHasText() {
		assertNull(SeerrText.shownProblem("scheme", "seerr.local", focused = true))
		assertNull(SeerrText.shownProblem("scheme", "  ", focused = false))
		assertEquals("The address must start with https:// or http://.", SeerrText.shownProblem("scheme", "seerr.local", focused = false))
		assertNull(SeerrText.shownProblem(null, "https://seerr.local", focused = false))
	}

	@Test
	fun everyKindOfFailureHasItsOwnWords() {
		val kinds = listOf(
			"unreachable", "timedOut", "notSeerr", "badKey", "notSignedIn", "plexAccountNotAllowed", "plexSignInFailed",
			"notAllowed", "quotaReached", "alreadyRequested", "notFound",
		)
		val words = kinds.map { SeerrText.failure(seerr(it)) }
		assertEquals(kinds.size, words.toSet().size)
		assertTrue(SeerrText.failure(seerr("badKey")).startsWith("Seerr refused the key."))
	}

	@Test
	fun anErrorFromSeerrCarriesItsMessage() {
		assertEquals("Seerr answered with an error: Boom", SeerrText.failure(seerr("failed", "Boom")))
		assertEquals("Seerr answered with an error.", SeerrText.failure(seerr("failed", "seerr")))
	}

	@Test
	fun anUnreachableServerAndAStrangeErrorAreTold() {
		assertEquals(SeerrText.failure(seerr("unreachable")), SeerrText.failure(CoreException(CoreError("unreachable"))))
		assertEquals("Couldn't reach Seerr. Try again.", SeerrText.failure(IllegalStateException()))
	}

	@Test
	fun onlyAnAlreadyRequestedFailureIsAlreadyRequested() {
		assertTrue(SeerrText.alreadyRequested(seerr("alreadyRequested")))
		assertFalse(SeerrText.alreadyRequested(seerr("notAllowed")))
		assertFalse(SeerrText.alreadyRequested(IllegalStateException()))
	}

	@Test
	fun theTestLineFollowsTheCheck() {
		assertEquals(SeerrText.Tone.Neutral, SeerrText.status(false, SeerrText.Check.Testing).tone)
		assertEquals("Not tested yet.", SeerrText.status(true, SeerrText.Check.Untested).text)
		assertEquals(
			"Connected · Seerr 2.0.0, signed in as Jordan.",
			SeerrText.status(true, SeerrText.Check.Connected("2.0.0", "Jordan")).text,
		)
		assertEquals(SeerrText.Tone.Problem, SeerrText.status(true, SeerrText.Check.Failed("No")).tone)
	}
}

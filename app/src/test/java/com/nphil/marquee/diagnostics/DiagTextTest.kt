package com.nphil.marquee.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTextTest {
	@Test
	fun aProblemShowsOnceItsFieldIsLeftAndNotWhileTyping() {
		val draft = DiagDraft(server = "ftp://x")
		val left = setOf(DiagField.Server)
		assertNull(DiagText.shownProblem(DiagProblem.Scheme, draft, emptySet(), null))
		assertNull(DiagText.shownProblem(DiagProblem.Scheme, draft, left, DiagField.Server))
		assertNull(DiagText.shownProblem(DiagProblem.Scheme, DiagDraft(), left, null))
		assertNull(DiagText.shownProblem(null, draft, left, null))
		assertEquals(DiagProblem.Scheme, DiagText.shownProblem(DiagProblem.Scheme, draft, left, null))
		assertEquals(DiagProblem.Scheme, DiagText.shownProblem(DiagProblem.Scheme, draft, left, DiagField.Topic))
	}

	@Test
	fun eachProblemBelongsToItsField() {
		assertEquals(DiagField.Topic, DiagText.fieldOf(DiagProblem.Topic))
		assertEquals(DiagField.Token, DiagText.fieldOf(DiagProblem.Token))
		listOf(DiagProblem.Scheme, DiagProblem.Host, DiagProblem.Plaintext).forEach {
			assertEquals(DiagField.Server, DiagText.fieldOf(it))
		}
		assertEquals(DiagProblem.Plaintext, DiagProblem.of("plaintext"))
		assertNull(DiagProblem.of(null))
		assertNull(DiagProblem.of("unheard of"))
	}

	@Test
	fun statusSaysWhereTheTestStands() {
		assertTrue(DiagText.status(false, DiagCheck.Untested).text.startsWith("Not set up"))
		assertEquals("Not tested yet.", DiagText.status(true, DiagCheck.Untested).text)
		assertTrue(DiagText.status(true, DiagCheck.Testing).text.startsWith("Checking"))
		assertEquals(DiagText.Tone.Good, DiagText.status(true, DiagCheck.Done(DiagTest.Sent)).tone)
		assertEquals(DiagText.Tone.Problem, DiagText.status(true, DiagCheck.Done(DiagTest.Unreachable)).tone)
		assertTrue(DiagText.status(true, DiagCheck.Done(DiagTest.Failed(503))).text.contains("(503)"))
		assertFalse(DiagText.status(true, DiagCheck.Done(DiagTest.Failed(null))).text.contains("("))
	}

	@Test
	fun theLiveLineIsForTroubleAndTheLastSend() {
		val time = { millis: Long -> "t$millis" }
		assertNull(DiagText.live(null, time))
		assertNull(DiagText.live(DiagStatus(DiagStatus.State.Off, null, null, false), time))
		assertNull(DiagText.live(DiagStatus(DiagStatus.State.Sending, null, null, false), time))
		assertEquals("Live logs last sent t5.", DiagText.live(DiagStatus(DiagStatus.State.Sending, 5, null, false), time)?.text)
		assertEquals(DiagText.Tone.Neutral, DiagText.live(DiagStatus(DiagStatus.State.Retrying, null, null, true), time)?.tone)
		assertEquals(
			DiagText.Tone.Problem,
			DiagText.live(DiagStatus(DiagStatus.State.Refused, null, DiagStatus.Refusal.NeedsToken, false), time)?.tone,
		)
	}

	@Test
	fun theDestinationNamesTheHostOrFallsBack() {
		assertEquals(
			"It goes to the topic logs on ntfy.example.com. Anyone who can read that topic can read the report.",
			DiagText.destination("logs", " https://ntfy.example.com/ "),
		)
		assertTrue(DiagText.destination("logs", "not an address").contains("on the server."))
	}

	@Test
	fun theSentLineSaysWhetherTheLogWasAttached() {
		assertEquals("Sent as report r7, with the log attached.", DiagText.sent("r7", true))
		assertTrue(DiagText.sent("r7", false).contains("went as messages"))
	}

	@Test
	fun namesFallBackToTheProfileAndTheKindOfDevice() {
		assertEquals("Android tablet", DiagNames.default(null, true))
		assertEquals("Android phone", DiagNames.default("  ", false))
		assertEquals("Nitin’s tablet", DiagNames.default("Nitin", true))
		assertEquals("Lounge", DiagNames.device(" Lounge ", "Nitin", true))
		assertEquals("Nitin’s tablet", DiagNames.device("", "Nitin", true))
		assertEquals("Marquee 0.2.0 · Lounge", DiagNames.title("0.2.0", "Lounge"))
	}
}

package com.nphil.marquee.seerr

import com.nphil.marquee.core.CoreError
import com.nphil.marquee.core.CoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrConfigTest {
	private val saved = Seerr.Config(address = "https://seerr.local", on = true)

	@Test
	fun theSwitchOnWithoutASetupShowsNoRequests() {
		assertFalse(Seerr.Config(on = true).enabled)
	}

	@Test
	fun aConnectionThatWorkedShowsRequestsAndOneThatFailedHidesThem() {
		assertTrue(saved.copy(verified = true).enabled)
		assertFalse(saved.copy(verified = false).enabled)
	}

	@Test
	fun aWorkingConnectionStaysHiddenWhileTheSwitchIsOff() {
		assertFalse(saved.copy(on = false, verified = true).enabled)
	}

	@Test
	fun anAddressWithoutASchemeIsTriedAsHttpsThenHttp() {
		assertEquals(listOf("https://seerr.local", "http://seerr.local"), SeerrText.candidates(" seerr.local "))
		assertEquals(listOf("http://seerr.local:5055"), SeerrText.candidates("http://seerr.local:5055"))
	}

	@Test
	fun theOtherSchemeIsTriedOnlyWhenNothingAnsweredLikeSeerr() {
		fun seerr(failure: String) = CoreException(CoreError("seerr", failure = failure))
		assertTrue(SeerrText.triesOtherScheme(seerr("unreachable")))
		assertTrue(SeerrText.triesOtherScheme(seerr("notSeerr")))
		assertFalse(SeerrText.triesOtherScheme(seerr("badKey")))
		assertFalse(SeerrText.triesOtherScheme(IllegalStateException()))
	}
}

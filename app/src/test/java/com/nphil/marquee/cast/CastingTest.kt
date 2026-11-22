package com.nphil.marquee.cast

import com.nphil.marquee.core.CastStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingTest {
	@Test fun oneChooserKeepsMatchingNamesAndNativeIdsOnDifferentProviders() {
		val google = CastReceiver(CastRouteId(CastProvider.Chromecast, "living-room"), "Living Room", null)
		val airPlay = google.copy(id = CastRouteId(CastProvider.AirPlay, "living-room"))
		assertEquals(listOf(google, airPlay), castChoices(listOf(google, airPlay), pending = airPlay, current = google))
		assertEquals(listOf(google, airPlay), castChoices(listOf(google), pending = airPlay, current = google))
		assertEquals(listOf(airPlay), castChoices(emptyList(), pending = null, current = airPlay))
	}

	@Test fun stagedHandoffWaitsForTheRequestedStateOfItsOwnLoad() {
		val loading = CastStatus(8, "loading", position = 90.0, rate = 0.0)
		assertFalse(loading.resolvesHandoff(8, "paused"))
		assertFalse(loading.copy(player = "buffering").resolvesHandoff(8, "paused"))
		val paused = loading.copy(player = "paused")
		assertFalse(paused.resolvesHandoff(7, "paused"))
		assertTrue(paused.resolvesHandoff(8, "paused"))
		assertFalse(paused.resolvesHandoff(8, "playing"))
		assertTrue(paused.copy(player = "playing", rate = 1.0).resolvesHandoff(8, "playing"))
	}

	@Test fun aTerminalStatusReleasesOnlyTheHandoffItBelongsTo() {
		val failed = CastStatus(8, "idle", "error", 90.0, 0.0)
		assertTrue(failed.resolvesHandoff(8, "paused"))
		assertFalse(failed.resolvesHandoff(9, "paused"))
	}
}

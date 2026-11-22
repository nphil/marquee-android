package com.nphil.marquee.cast

import com.nphil.marquee.cast.CastLoadStamp.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastLoadStampTest {
	@Test
	fun aReloadIgnoresLateStatusesFromThePreviousTitle() {
		val load = CastLoadStamp(serial = 2, id = "new", previous = "old")

		assertEquals(Status.Ignore, load.observe("old", idle = true))
		assertTrue(load.canStop("old"))
		assertEquals(Status.Current, load.observe("new", idle = false))
		assertEquals(Status.Ignore, load.observe("old", idle = true))
		assertFalse(load.canStop("old"))
		assertTrue(load.owns("new"))
	}

	@Test
	fun aDifferentSenderTakingOverIsNotStopped() {
		val load = CastLoadStamp(serial = 1, id = "ours", previous = null)

		assertEquals(Status.Ignore, load.observe("theirs", idle = false))
		assertFalse(load.canStop("theirs"))
		assertFalse(load.canStop(null))
		assertEquals(Status.Current, load.observe("ours", idle = false))
		assertEquals(Status.Replaced, load.observe("theirs", idle = false))
		assertFalse(load.canStop("theirs"))
	}

	@Test
	fun anIdleStatusWithoutMediaEndsOnlyAnObservedLoad() {
		val load = CastLoadStamp(serial = 1, id = "ours", previous = null)

		assertEquals(Status.Ignore, load.observe(null, idle = true))
		assertEquals(Status.Current, load.observe("ours", idle = false))
		assertEquals(Status.Current, load.observe(null, idle = true))
	}
}

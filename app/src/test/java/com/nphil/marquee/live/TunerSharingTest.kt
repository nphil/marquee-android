package com.nphil.marquee.live

import com.nphil.marquee.core.CoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerSharingTest {
	private fun status(viewers: List<String>, holder: String = "null") = CoreJson.parseToJsonElement(
		"""{"holder":$holder,"viewers":[${viewers.joinToString(",") { """{"ip":"192.0.2.9","channel":"$it"}""" }}],""" +
			""""sameRf":[{"rf":29,"channels":["2.1","2.2"]}]}""",
	)

	@Test
	fun sameRfSharesAndOtherRfDoesNot() {
		assertTrue(TunerSharing.shares(status(listOf("2.1")), "2.2"))
		assertTrue(TunerSharing.shares(status(listOf("7.1")), "7.1"))
		assertFalse(TunerSharing.shares(status(listOf("2.1")), "9.1"))
		assertNull(TunerSharing.refusalMessage(status(listOf("2.1", "2.2", "2.1")), "2.2"))
		assertEquals(TunerSharing.FULL_MESSAGE, TunerSharing.refusalMessage(status(listOf("2.1", "2.2", "2.1", "2.2")), "2.1"))
		assertEquals(
			TunerSharing.RECORDING_MESSAGE,
			TunerSharing.refusalMessage(status(listOf("2.1"), """{"name":"Plex Media Server"}"""), "9.1"),
		)
	}

	@Test
	fun missingFieldsAreTodaysBehaviour() {
		val old = CoreJson.parseToJsonElement("""{"holder":{"name":"Marquee"}}""")
		assertFalse(TunerSharing.shares(old, "2.1"))
		assertNull(TunerSharing.refusalMessage(old, "2.1"))
		assertFalse(TunerSharing.shares(null, "2.1"))
	}
}

package com.nphil.marquee.live

import com.nphil.marquee.core.CoreJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunerTakeoverTest {
	private val now = 1_800_000_100_000L

	private fun status(entries: String) = CoreJson.parseToJsonElement("""{"tuners":[],"displaced":[$entries]}""")

	@Test
	fun onlyThisClientsRecentEntryMatches() {
		val body = status(
			"""{"ip":"192.0.2.9","name":"NatePad","by":"Kitchen iPad","byKind":"app","at":${now - 10_000},"channel":"2.1"},
			{"name":"Nitin’s iPhone","by":"","byKind":"tv","at":${now - 5_000}},
			{"name":"NatePad","by":"Old","byKind":"app","at":${now - 70_000}}""",
		)
		assertEquals("Kitchen iPad is now using the TV tuner.", TunerTakeover.match(body, " NatePad ", now)?.message)
		assertEquals("The TV is now using its tuner.", TunerTakeover.match(body, "Nitin’s iPhone", now)?.message)
		assertNull(TunerTakeover.match(body, "Other", now))
		assertNull(TunerTakeover.match(body, "NatePad", now + 120_000))
	}

	@Test
	fun oldTvsAndOtherDocumentsMatchNothing() {
		assertNull(TunerTakeover.match(CoreJson.parseToJsonElement("""{"holder":null}"""), "NatePad", now))
		assertNull(TunerTakeover.match(CoreJson.parseToJsonElement("""[{"VctNumber":"2.1"}]"""), "NatePad", now))
		assertNull(TunerTakeover.match(null, "NatePad", now))
	}

	@Test
	fun theNameIsEncodedAndCutToFortyCharacters() {
		val url = "http://192.0.2.42:5004/auto/v2.1".toHttpUrl()
		assertEquals("A%2BB%20%26%20C", TunerTakeover.named(url, "A+B & C").encodedQuery?.removePrefix("device="))
		assertEquals("x".repeat(40), TunerTakeover.named(url, "x".repeat(60)).queryParameter("device"))
	}
}

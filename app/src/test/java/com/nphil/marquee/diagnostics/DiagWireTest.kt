package com.nphil.marquee.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagWireTest {
	@Test
	fun categoryAndLevelNamesAreTheBridgesOwn() {
		assertEquals(
			listOf("app", "ui", "nav", "net", "plex", "player", "audio", "subtitles", "offers", "download", "live", "link", "bigscreen", "integration"),
			DiagCategory.entries.map { it.wire },
		)
		assertEquals(listOf(0, 1, 2, 3, 4), DiagLevel.entries.map { it.wire })
	}

	@Test
	fun valuesGoAsTheyAre() {
		assertEquals(JsonNull, DiagWire.scalar(null))
		assertEquals(JsonPrimitive(true), DiagWire.scalar(true))
		assertEquals(JsonPrimitive(7L), DiagWire.scalar(7))
		assertEquals(JsonPrimitive(9_000_000_000L), DiagWire.scalar(9_000_000_000L))
		assertEquals(JsonPrimitive(1.5), DiagWire.scalar(1.5))
		assertEquals(JsonPrimitive("NaN"), DiagWire.scalar(Double.NaN))
		assertEquals(JsonPrimitive("abc"), DiagWire.scalar("abc"))
		assertEquals(JsonPrimitive("IllegalStateException: boom"), DiagWire.scalar(IllegalStateException("boom")))
		assertEquals(JsonPrimitive("IllegalStateException"), DiagWire.scalar(IllegalStateException()))
	}

	@Test
	fun anEventSkipsFieldsWithoutAValue() {
		val event = DiagWire.event(
			1_700_000_000_123L, DiagLevel.Warning, DiagCategory.Plex, "signin.failed",
			arrayOf("kind" to "Unreachable", "status" to null, "n" to 3),
		)
		assertEquals(
			"""{"o":"e","t":1700000000123,"l":3,"c":"plex","n":"signin.failed","f":[["kind","Unreachable"],["n",3]]}""",
			event.toString(),
		)
	}

	@Test
	fun configureCarriesTheServerOnlyOnceItIsSetUp() {
		assertEquals(
			"""{"live":false,"title":"T","device":"D","detailed":true}""",
			DiagWire.configure(DiagConfig(live = false, detailed = true), "T", "D").toString(),
		)
		assertEquals(
			"""{"server":"https://ntfy.example","topic":"marquee","token":"tk_1","live":true,"title":"T","device":"D","detailed":false}""",
			DiagWire.configure(DiagConfig(server = " https://ntfy.example ", topic = "marquee", token = " tk_1 "), "T", "D").toString(),
		)
		val withoutToken = DiagWire.configure(DiagConfig(server = "https://ntfy.example", topic = "marquee", token = "  "), "T", "D")
		assertFalse("token" in withoutToken)
		assertFalse("server" in DiagWire.configure(DiagConfig(server = "https://ntfy.example"), "T", "D"))
	}

	@Test
	fun aReportCarriesItsScreenshotAsBase64() {
		val report = DiagWire.report("note", "button", "details", "tab=Library path=root sheets=-", byteArrayOf(1, 2, 3))
		assertEquals("AQID", report.getValue("screenshot").jsonPrimitive.content)
		assertFalse("screenshot" in DiagWire.report("note", "settings", "details", "screen", null))
	}

	@Test
	fun theBridgesAnswersBecomeTheirTypes() {
		val retrying = DiagWire.status(DiagWire.decode<StateDto>(Json.parseToJsonElement("""{"state":"retrying","status":503,"waiting":2}""")))
		assertEquals(DiagStatus.State.Retrying, retrying.state)
		assertEquals(true, retrying.waiting)
		assertEquals(1_700_000_000_123L, DiagWire.status(StateDto("sending", lastSent = 1_700_000_000_123.0)).lastSentMillis)
		val refused = DiagWire.status(StateDto("refused", refusal = "needsToken"))
		assertEquals(DiagStatus.State.Refused, refused.state)
		assertEquals(DiagStatus.Refusal.NeedsToken, refused.refusal)
		assertEquals(DiagStatus.State.Off, DiagWire.status(StateDto("off")).state)

		assertEquals(DiagTest.Sent, DiagWire.test(TestDto("sent")))
		assertEquals(DiagTest.NeedsToken, DiagWire.test(TestDto("needsToken")))
		assertEquals(DiagTest.Unreachable, DiagWire.test(TestDto("unreachable")))
		assertEquals(DiagTest.Failed(503), DiagWire.test(TestDto("failed", 503)))
	}
}

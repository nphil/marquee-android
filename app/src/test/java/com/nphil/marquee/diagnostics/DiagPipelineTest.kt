package com.nphil.marquee.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagPipelineTest {
	private val start = DiagWire.start("/files/Diagnostics", "0.2.0", "42", "abc1234", 1_000L, 3600)
	private val failures = mutableListOf<String>()

	private fun pipeline(bridge: FakeBridge, begun: Boolean = true) =
		DiagPipeline(bridge, wallMillis = { 5_000L }, offsetSeconds = { 3600 }, onFailure = { method, _ -> failures += method })
			.also { if (begun) it.begin(start) }

	private fun DiagPipeline.log(name: String, level: DiagLevel = DiagLevel.Info) =
		event(level, DiagCategory.App, name, emptyArray<Pair<String, Any?>>())

	@Test
	fun eventsGoAfterTheStartInOrder() = runBlocking {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		pipeline.log("one")
		pipeline.log("two", DiagLevel.Warning)
		pipeline.sync()
		job.cancel()
		assertEquals(listOf("diag.start", "diag.events"), bridge.methods)
		assertEquals(listOf("one", "two"), bridge.calls[1].ops.map { it.name() })
		assertEquals(3600, bridge.calls[1].payload.getValue("offset").jsonPrimitive.content.toInt())
	}

	@Test
	fun aCallKeepsItsPlaceBetweenEvents() = runBlocking {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		pipeline.log("a")
		pipeline.phase("background")
		pipeline.log("b")
		pipeline.flush()
		pipeline.sync()
		job.cancel()
		assertEquals(listOf("diag.start", "diag.events", "diag.phase", "diag.events", "diag.flush"), bridge.methods)
		assertEquals(listOf("a"), bridge.calls[1].ops.map { it.name() })
		assertEquals(listOf("b"), bridge.calls[3].ops.map { it.name() })
	}

	@Test
	fun debugIsKeptOnlyInDetailed() = runBlocking {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		pipeline.log("quiet", DiagLevel.Debug)
		pipeline.log("kept")
		pipeline.configure(DiagWire.configure(DiagConfig(detailed = true), "T", "D"), detailed = true)
		pipeline.log("loud", DiagLevel.Debug)
		pipeline.sync()
		job.cancel()
		val names = bridge.calls.filter { it.method == "diag.events" }.flatMap { it.ops }.map { it.name() }
		assertEquals(listOf("kept", "loud"), names)
	}

	@Test
	fun eventsGoInBatchesOfAtMostTwoHundred() = runBlocking {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		repeat(450) { pipeline.log("e$it") }
		val job = pipeline.run(this)
		pipeline.sync()
		job.cancel()
		assertEquals(listOf(200, 200, 50), bridge.calls.filter { it.method == "diag.events" }.map { it.ops.size })
		assertEquals("e449", bridge.calls.last().ops.last().name())
	}

	@Test
	fun playbacksAreNumberedAndBracketTheirEvents() = runBlocking {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		assertEquals("p1", pipeline.beginPlayback())
		pipeline.log("inside")
		pipeline.endPlayback()
		assertEquals("p2", pipeline.beginPlayback())
		pipeline.sync()
		job.cancel()
		val ops = bridge.calls[1].ops
		assertEquals(listOf("b", "e", "x", "b"), ops.map { it.getValue("o").jsonPrimitive.content })
		assertEquals("p1", ops[0].getValue("id").jsonPrimitive.content)
		assertEquals("p2", ops[3].getValue("id").jsonPrimitive.content)
	}

	@Test
	fun aFailedStartIsRetriedWithTheNextBatch() = runBlocking {
		val bridge = FakeBridge(startFailures = 1)
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		pipeline.log("lost")
		pipeline.sync()
		pipeline.log("kept")
		pipeline.sync()
		job.cancel()
		assertEquals(listOf("diag.start"), failures)
		assertEquals(listOf("diag.start", "diag.start", "diag.events"), bridge.methods)
		assertEquals(listOf("kept"), bridge.calls[2].ops.map { it.name() })
	}

	@Test
	fun aRequestStartsTheLogAndGetsItsAnswer() = runBlocking {
		val reply = JsonObject(mapOf("text" to JsonPrimitive("the journal")))
		val bridge = FakeBridge(replies = mapOf("diag.journal" to reply))
		val pipeline = pipeline(bridge)
		val job = pipeline.run(this)
		pipeline.log("before")
		assertEquals(reply, pipeline.request("diag.journal", DiagWire.journal(100)))
		job.cancel()
		assertEquals(listOf("diag.start", "diag.events", "diag.journal"), bridge.methods)
	}

	@Test
	fun aRequestFailsWhenTheLogCannotStart() = runBlocking {
		val pipeline = pipeline(FakeBridge(), begun = false)
		val job = pipeline.run(this)
		try {
			pipeline.request("diag.journal")
			fail("Expected the request to fail.")
		} catch (_: IllegalStateException) {
		}
		job.cancel()
	}

	@Test
	fun aCrashSendsWhatIsQueuedAndThenItselfInOneCall() {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		pipeline.log("queued")
		pipeline.crash(DiagCrash.events("main", IllegalStateException("boom"), 9_000L))
		assertEquals(listOf("diag.start", "diag.crash"), bridge.methods)
		val names = bridge.calls[1].ops.map { it.name() }
		assertEquals(listOf("queued", "crash"), names.take(2))
		assertTrue(names.drop(2).all { it == "crash.frame" })
	}

	@Test
	fun aFullQueueDropsItsOldest() {
		val bridge = FakeBridge()
		val pipeline = pipeline(bridge)
		repeat(10_050) { pipeline.log("e$it") }
		pipeline.crash(emptyList())
		val ops = bridge.calls.single { it.method == "diag.crash" }.ops
		assertEquals(10_000, ops.size)
		assertEquals("e50", ops.first().name())
		assertEquals("e10049", ops.last().name())
	}
}

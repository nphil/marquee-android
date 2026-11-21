package com.nphil.marquee.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagWatchTest {
	@Test
	fun onlyTheTrimLevelsWorthALineHaveNames() {
		assertNull(DiagWatch.trimName(20))
		assertEquals("running low", DiagWatch.trimName(10))
		assertEquals(DiagLevel.Warning, DiagWatch.trimLevel(15))
		assertEquals(DiagLevel.Notice, DiagWatch.trimLevel(5))
	}

	@Test
	fun heatGetsLouderAsItRises() {
		assertEquals("severe", DiagWatch.thermalName(3))
		assertEquals("9", DiagWatch.thermalName(9))
		assertEquals(DiagLevel.Info, DiagWatch.thermalLevel(1))
		assertEquals(DiagLevel.Notice, DiagWatch.thermalLevel(2))
		assertEquals(DiagLevel.Warning, DiagWatch.thermalLevel(3))
	}

	@Test
	fun theNetworkReadsAsOneLine() {
		assertEquals("wifi", DiagWatch.networkText(listOf("wifi"), validated = true, metered = false))
		assertEquals("wifi+vpn · no internet · metered", DiagWatch.networkText(listOf("wifi", "vpn"), validated = false, metered = true))
		assertEquals("other", DiagWatch.networkText(emptyList(), validated = true, metered = false))
	}

	@Test
	fun aPastExitIsNamedAndRankedByWhatItMeans() {
		assertEquals("native crash", DiagExits.reasonName(5))
		assertEquals("unknown", DiagExits.reasonName(99))
		assertEquals(DiagLevel.Warning, DiagExits.level(6))
		assertEquals(DiagLevel.Notice, DiagExits.level(2))
		assertEquals(DiagLevel.Info, DiagExits.level(10))
		assertEquals("cached", DiagExits.importanceName(400))
		assertEquals("7", DiagExits.importanceName(7))
	}

	@Test
	fun anAnrTraceYieldsTheMainThreadsFrames() {
		val trace = """
			----- pid 1 at 2026-01-01 -----
			"Signal Catcher" daemon prio=10 tid=2 Runnable
			  at not.the.main.One(Skip.kt:1)

			"main" prio=5 tid=1 Blocked
			  | group="main" sCount=1
			  at java.lang.Object.wait(Native method)
			  at com.nphil.marquee.Foo.bar(Foo.kt:12)

			"other" prio=5 tid=3 Waiting
			  at not.main.Either(Skip.kt:2)
		""".trimIndent()
		assertEquals(
			listOf("at java.lang.Object.wait(Native method)", "at com.nphil.marquee.Foo.bar(Foo.kt:12)"),
			DiagExits.anrFrames(trace),
		)
		assertEquals(1, DiagExits.anrFrames(trace, limit = 1).size)
		assertTrue(DiagExits.anrFrames("no main thread here").isEmpty())
	}

	@Test
	fun aCrashIsOneErrorThenItsTopFrames() {
		val error = IllegalStateException("boom", RuntimeException("root"))
		val events = DiagCrash.events("main", error, 9_000L)
		val first = events.first().toString()
		assertEquals("crash", events.first().name())
		assertTrue(first.contains(""""l":4"""))
		assertTrue(first.contains("""["thread","main"]"""))
		assertTrue(first.contains("""["exception","java.lang.IllegalStateException"]"""))
		assertTrue(first.contains("""["message","boom"]"""))
		assertTrue(first.contains("""["cause","RuntimeException: root"]"""))
		assertTrue(events.size in 2..9)
		assertTrue(events.drop(1).all { it.name() == "crash.frame" })
	}

	@Test
	fun aRequestIsLoggedByItsStatusWithoutItsQuery() {
		assertEquals(DiagLevel.Debug, DiagRequestLog.level(200))
		assertEquals(DiagLevel.Debug, DiagRequestLog.level(304))
		assertEquals(DiagLevel.Notice, DiagRequestLog.level(404))
		assertEquals(DiagLevel.Warning, DiagRequestLog.level(503))
		assertEquals(
			"https://plex.example/library/sections/1/all",
			DiagRequestLog.address("https://plex.example/library/sections/1/all?X-Plex-Token=abc#frag".toHttpUrl()),
		)
	}

	@Test
	fun theScreenReadsAsOneLine() {
		assertEquals("tab=Library path=root sheets=-", DiagScreen.summary("Library", null, emptyList()))
		assertEquals(
			"tab=Settings path=Diagnostic Logs sheets=report,forget",
			DiagScreen.summary("Settings", "Diagnostic Logs", listOf("report", "forget")),
		)
	}

	@Test
	fun aScreenshotIsScaledDownToItsLongSide() {
		assertEquals(1400 to 875, DiagScreenshot.scaledSize(2560, 1600))
		assertEquals(875 to 1400, DiagScreenshot.scaledSize(1600, 2560))
		assertEquals(800 to 600, DiagScreenshot.scaledSize(800, 600))
		assertEquals(1 to 1, DiagScreenshot.scaledSize(0, 0))
	}
}

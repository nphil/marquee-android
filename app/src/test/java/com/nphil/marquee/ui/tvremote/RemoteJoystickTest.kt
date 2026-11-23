package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteJoystickTest {
	private val stick = JoystickRepeater()

	/** Holds the stick at a steady lean on a 1 ms clock and lists when and where it stepped. */
	private fun lean(dx: Float, dy: Float, from: Long, to: Long): List<Pair<Long, DpadKey>> =
		(from..to).mapNotNull { now -> stick.update(now, dx, dy)?.let { now to it } }

	private fun gaps(steps: List<Pair<Long, DpadKey>>) = steps.drop(1).map { it.first }.zipWithNext { a, b -> b - a }

	@Test
	fun aLeanStepsAtOnceThenWaitsOutTheTvsHoldDelay() {
		assertEquals(listOf(0L to DpadKey.Right, 300L to DpadKey.Right), lean(0.5f, 0f, 0, 300))
	}

	@Test
	fun aFirmLeanWaitsLess() {
		assertEquals(listOf(0L, 140L), lean(0.8f, 0f, 0, 140).map { it.first })
	}

	@Test
	fun theRepeatRateRisesWithDeflectionFromCrawlToFull() {
		val light = gaps(lean(0.3f, 0f, 0, 4000))
		assertEquals(308.5, light.average(), 3.0)
		stick.release()
		val full = gaps(lean(1f, 0f, 5000, 9000))
		assertEquals(80.0, full.average(), 1.0)
		assertTrue("never faster than the fastest rate", full.all { it >= 78 })
	}

	@Test
	fun theRepeatPeriodFallsSmoothlyFromSlowToFast() {
		assertEquals(JoystickRepeater.SlowMs, JoystickRepeater.periodMs(0f), 0.01f)
		assertEquals(JoystickRepeater.FastMs, JoystickRepeater.periodMs(1f), 0.01f)
		val periods = (0..10).map { JoystickRepeater.periodMs(it / 10f) }
		assertEquals(periods.sortedDescending(), periods)
		assertEquals(0f, JoystickRepeater.strength(JoystickRepeater.DeadIn), 0f)
		assertEquals(1f, JoystickRepeater.strength(1.5f), 0f)
	}

	@Test
	fun aStalledFrameYieldsOneStepNotABurst() {
		lean(1f, 0f, 0, 140)
		assertEquals(DpadKey.Right, stick.update(1140, 1f, 0f))
		val next = lean(1f, 0f, 1141, 1400).first().first
		assertTrue("next step came ${next - 1140} ms later", next - 1140 in 38..42)
	}

	@Test
	fun theStickLetsGoOnlyBelowTheInnerDeadZone() {
		assertNull(stick.update(0, 0.2f, 0f))
		assertEquals(DpadKey.Right, stick.update(10, 0.3f, 0f))
		assertNull(stick.update(20, 0.18f, 0f))
		assertEquals(DpadKey.Right, stick.held)
		assertNull(stick.update(30, 0.1f, 0f))
		assertNull(stick.held)
		assertEquals(DpadKey.Right, stick.update(40, 0.3f, 0f))
	}

	@Test
	fun theStickChangesAxisOnlyWhenTheNewOneLeads() {
		assertEquals(DpadKey.Right, stick.update(0, 0.5f, 0f))
		assertNull(stick.update(10, 0.5f, 0.55f))
		assertEquals(DpadKey.Right, stick.held)
		assertEquals(DpadKey.Down, stick.update(20, 0.5f, 0.7f))
		assertEquals(DpadKey.Left, stick.update(30, -0.5f, 0.1f))
	}

	@Test
	fun flippingToTheOppositeArrowIsImmediate() {
		assertEquals(DpadKey.Left, stick.update(0, -0.5f, 0f))
		assertEquals(DpadKey.Right, stick.update(10, 0.3f, 0.2f))
	}

	@Test
	fun releasingTheStickRestartsTheHoldDelay() {
		lean(0.5f, 0f, 0, 500)
		stick.release()
		assertNull(stick.held)
		assertEquals(DpadKey.Up, stick.update(600, 0f, -0.5f))
		assertNull(stick.update(700, 0f, -0.5f))
	}

	@Test
	fun theDiscMapsATouchToOkOrTheNearestArrow() {
		val radius = 100f
		assertEquals(DpadKey.Ok, dpadKeyAt(Offset.Zero, radius))
		assertEquals(DpadKey.Ok, dpadKeyAt(Offset(41f, 0f), radius))
		assertEquals(DpadKey.Right, dpadKeyAt(Offset(43f, 0f), radius))
		assertEquals(DpadKey.Up, dpadKeyAt(Offset(10f, -60f), radius))
		assertEquals(DpadKey.Left, dpadKeyAt(Offset(-60f, 20f), radius))
		assertEquals(DpadKey.Down, dpadKeyAt(Offset(0f, 109f), radius))
		assertNull(dpadKeyAt(Offset(0f, 111f), radius))
	}

	@Test
	fun theThumbDeflectsTheStickInProportionAndStopsAtFullReach() {
		assertEquals(0.5f, stickDeflection(Offset(30f, 0f), 60f).x, 1e-6f)
		val far = stickDeflection(Offset(300f, 400f), 100f)
		assertEquals(0.6f, far.x, 1e-6f)
		assertEquals(0.8f, far.y, 1e-6f)
		assertEquals(Offset.Zero, stickDeflection(Offset(5f, 5f), 0f))
	}
}

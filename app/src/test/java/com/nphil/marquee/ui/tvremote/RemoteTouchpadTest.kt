package com.nphil.marquee.ui.tvremote

import com.nphil.marquee.tvremote.RemoteRequest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTouchpadTest {
	private val pad = TouchpadTracker()

	private fun move(events: List<PadEvent>) = events.filterIsInstance<PadEvent.Move>().single()

	@Test
	fun thePointerGainRisesWithFingerSpeedAndIsCapped() {
		assertEquals(1.2, TouchpadTracker.gain(0.0), 1e-9)
		assertEquals(2.2, TouchpadTracker.gain(500.0), 1e-9)
		assertEquals(4.0, TouchpadTracker.gain(10_000.0), 1e-9)
		val gains = (0..20).map { TouchpadTracker.gain(it * 100.0) }
		assertEquals(gains.sorted(), gains)
	}

	@Test
	fun aQuickStrokeTravelsFurtherThanASlowOneOfTheSameLength() {
		pad.update(1, 0.0, 0.0, 0.0)
		val slow = move(pad.update(1, 10.0, 0.0, 1.0))
		pad.update(0, 10.0, 0.0, 1.0)
		val fast = TouchpadTracker().let {
			it.update(1, 0.0, 0.0, 0.0)
			move(it.update(1, 10.0, 0.0, 0.05))
		}
		assertEquals(12.2, slow.dx, 1e-9)
		assertEquals(16.0, fast.dx, 1e-9)
		val flick = TouchpadTracker().let {
			it.update(1, 0.0, 0.0, 0.0)
			move(it.update(1, 100.0, 0.0, 0.01))
		}
		assertEquals(400.0, flick.dx, 1e-9)
	}

	@Test
	fun sensitivityScalesTheMotion() {
		val doubled = TouchpadTracker(sensitivity = 2.0)
		doubled.update(1, 0.0, 0.0, 0.0)
		assertEquals(24.4, move(doubled.update(1, 10.0, 0.0, 1.0)).dx, 1e-9)
	}

	@Test
	fun aShortTapClicks() {
		assertTrue(pad.update(1, 50.0, 50.0, 1.0).isEmpty())
		assertEquals(listOf(PadEvent.Click), pad.update(0, 50.0, 50.0, 1.1))
	}

	@Test
	fun aLongTouchOrAWanderIsNotATap() {
		pad.update(1, 50.0, 50.0, 1.0)
		assertTrue(pad.update(0, 50.0, 50.0, 1.4).isEmpty())
		pad.update(1, 50.0, 50.0, 2.0)
		pad.update(1, 70.0, 50.0, 2.05)
		assertTrue(pad.update(0, 70.0, 50.0, 2.1).isEmpty())
	}

	@Test
	fun aTouchRightAfterATapDragsOnceItMoves() {
		pad.update(1, 50.0, 50.0, 1.0)
		pad.update(0, 50.0, 50.0, 1.1)
		pad.update(1, 50.0, 50.0, 1.2)
		assertTrue(pad.update(1, 54.0, 50.0, 1.25).single() is PadEvent.Move)
		val begun = pad.update(1, 70.0, 50.0, 1.3)
		assertEquals(PadEvent.Press, begun.first())
		assertTrue(begun.last() is PadEvent.Move)
		assertEquals(listOf(PadEvent.Release), pad.update(0, 70.0, 50.0, 1.4))
	}

	@Test
	fun aSecondQuickTapIsAClickAgain() {
		pad.update(1, 50.0, 50.0, 1.0)
		pad.update(0, 50.0, 50.0, 1.1)
		pad.update(1, 50.0, 50.0, 1.2)
		assertEquals(listOf(PadEvent.Click), pad.update(0, 50.0, 50.0, 1.25))
	}

	@Test
	fun twoFingersScrollAgainstTheirMotion() {
		pad.update(2, 100.0, 100.0, 0.0)
		val scroll = pad.update(2, 110.0, 110.0, 0.02).single() as PadEvent.Scroll
		assertEquals(-20.0, scroll.dx, 1e-9)
		assertEquals(-20.0, scroll.dy, 1e-9)
	}

	@Test
	fun aQuickTwoFingerTapIsBack() {
		pad.update(2, 100.0, 100.0, 1.0)
		assertEquals(listOf(PadEvent.Back), pad.update(0, 100.0, 100.0, 1.1))
	}

	@Test
	fun aFingerJoiningOrLeavingDoesNotJumpTheScroll() {
		pad.update(2, 100.0, 100.0, 0.0)
		pad.update(2, 100.0, 110.0, 0.02)
		assertTrue(pad.update(1, 100.0, 300.0, 0.04).isEmpty())
		assertTrue(pad.update(2, 100.0, 300.0, 0.06).isEmpty())
	}

	private fun scrollFast(): Double {
		pad.update(2, 100.0, 100.0, 0.0)
		for (step in 1..6) pad.update(2, 100.0, 100.0 + 10 * step, 0.02 * step)
		return 0.02 * 6
	}

	@Test
	fun liftingFingersThatAreStillMovingFlings() {
		val last = scrollFast()
		val fling = pad.update(0, 100.0, 160.0, last + 0.016).single() as PadEvent.Fling
		assertTrue("speed ${fling.vy}", fling.vy < -900.0)
	}

	@Test
	fun fingersThatRestedOrWereSlowDoNotFling() {
		val last = scrollFast()
		assertTrue(pad.update(0, 100.0, 160.0, last + 0.2).isEmpty())
		val slow = TouchpadTracker()
		slow.update(2, 100.0, 100.0, 0.0)
		slow.update(2, 100.0, 101.0, 0.5)
		assertTrue(slow.update(0, 100.0, 101.0, 0.52).isEmpty())
	}

	@Test
	fun aFlingCoastsToAStopWhateverTheFrameRate() {
		fun coast(frame: Double): Pair<Double, Int> {
			val momentum = ScrollMomentum(0.0, 1000.0)
			var distance = 0.0
			var frames = 0
			while (frames < 2000) {
				distance += momentum.step(frame)?.second ?: break
				frames++
			}
			return distance to frames
		}
		val (sixty, frames) = coast(1 / 60.0)
		val (hundredTwenty, _) = coast(1 / 120.0)
		// 1000 px/s decaying by 0.998 per millisecond covers 1000 / 2.002 px, less the tail below the stop speed.
		assertTrue("coasted $sixty px", sixty in 470.0..500.0)
		assertTrue("took $frames frames", frames in 90..150)
		assertTrue(abs(sixty - hundredTwenty) < 2.0)
		assertNull(ScrollMomentum(0.0, 10.0).step(0.016))
	}

	private class Wire {
		val sent = mutableListOf<RemoteRequest>()
		val session = TouchpadSession { sent += it }
		val pointers get() = sent.map { it.pointer }
	}

	@Test
	fun theSessionEngagesOnTouchAndReleasesAfterTheLinger() {
		val wire = Wire()
		wire.session.touch(1, 50f, 50f, 0)
		wire.session.touch(0, 50f, 50f, 100)
		assertEquals(listOf("begin", "click"), wire.pointers)
		wire.session.tick(1099)
		assertTrue(wire.session.engaged)
		wire.session.tick(1100)
		assertEquals(listOf("begin", "click", "end"), wire.pointers)
		assertFalse(wire.session.engaged)
		assertFalse(wire.session.busy)
	}

	@Test
	fun motionIsCoalescedToOneReportPerMinimumGap() {
		val wire = Wire()
		wire.session.touch(1, 0f, 0f, 0)
		wire.session.touch(1, 10f, 0f, 20)
		wire.session.touch(1, 12f, 0f, 24)
		assertEquals(listOf("begin", "move"), wire.pointers)
		wire.session.tick(28)
		assertEquals(listOf("begin", "move", "move"), wire.pointers)
		assertEquals(22.0, wire.sent[1].x!!, 1e-9)
		assertEquals(4.4, wire.sent[2].x!!, 1e-9)
	}

	@Test
	fun aScrollReleasesThePointerWhenTheFingersLift() {
		val wire = Wire()
		wire.session.touch(2, 100f, 100f, 0)
		wire.session.touch(2, 100f, 110f, 20)
		wire.session.touch(0, 100f, 110f, 500)
		assertEquals(listOf("begin", "scroll", "end"), wire.pointers)
		assertEquals(-20.0, wire.sent[1].y!!, 1e-9)
	}

	@Test
	fun aFlingKeepsTheSessionEngagedUntilTheGlideEnds() {
		val wire = Wire()
		wire.session.touch(2, 100f, 100f, 0)
		for (step in 1..6) wire.session.touch(2, 100f, 100f + 10 * step, 20L * step)
		wire.session.touch(0, 100f, 160f, 136)
		assertTrue(wire.session.busy)
		assertFalse("end" in wire.pointers)
		var now = 136L
		while (wire.session.busy && now < 10_000) {
			now += 16
			wire.session.tick(now)
		}
		assertEquals("end", wire.pointers.last())
		assertTrue(wire.pointers.count { it == "scroll" } > 6)
		assertFalse(wire.session.busy)
	}

	@Test
	fun dragLockHoldsTheButtonFromFirstTouchToLastLift() {
		val wire = Wire()
		wire.session.lock = true
		wire.session.touch(1, 0f, 0f, 0)
		wire.session.touch(1, 20f, 0f, 50)
		wire.session.touch(0, 20f, 0f, 100)
		assertEquals(listOf("begin", "down", "move", "up"), wire.pointers)
	}

	@Test
	fun airMouseDropsTheFingersOwnMotion() {
		val wire = Wire()
		wire.session.air = true
		wire.session.touch(1, 0f, 0f, 0)
		wire.session.touch(1, 30f, 0f, 50)
		wire.session.touch(0, 30f, 0f, 100)
		assertEquals(listOf("begin"), wire.pointers)
	}

	@Test
	fun aTwoFingerTapSendsBack() {
		val wire = Wire()
		wire.session.touch(2, 100f, 100f, 0)
		wire.session.touch(0, 100f, 100f, 100)
		assertEquals("back", wire.sent.last().key)
	}

	@Test
	fun stoppingLetsGoOfTheButtonAndThePointer() {
		val wire = Wire()
		wire.session.lock = true
		wire.session.touch(1, 0f, 0f, 0)
		wire.session.stop()
		assertEquals(listOf("begin", "down", "up", "end"), wire.pointers)
		assertFalse(wire.session.engaged)
		assertFalse(wire.session.busy)
	}
}

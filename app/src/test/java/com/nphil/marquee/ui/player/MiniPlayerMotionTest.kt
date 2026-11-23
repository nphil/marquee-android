package com.nphil.marquee.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerMotionTest {
	@Test
	fun aDragTakesItsLargerMovePastTheSlop() {
		assertNull(dockAxis(dx = 3f, dy = 8f, slop = 8f))
		assertEquals(DockAxis.Vertical, dockAxis(dx = 3f, dy = -20f, slop = 8f))
		assertEquals(DockAxis.Horizontal, dockAxis(dx = -20f, dy = 3f, slop = 8f))
		assertEquals(DockAxis.Horizontal, dockAxis(dx = 12f, dy = 12f, slop = 8f))
	}

	@Test
	fun theExpansionFollowsTheFingerAndStaysBetweenTheRests() {
		assertEquals(0.75f, dockExpansion(start = 1f, dy = 100f, travel = 400f), 1e-6f)
		assertEquals(0.4f, dockExpansion(start = 0f, dy = -160f, travel = 400f), 1e-6f)
		assertEquals(0f, dockExpansion(start = 1f, dy = 900f, travel = 400f), 0f)
		assertEquals(1f, dockExpansion(start = 0f, dy = -900f, travel = 400f), 0f)
		assertEquals(0.5f, dockExpansion(start = 0.5f, dy = 100f, travel = 0f), 0f)
	}

	@Test
	fun aSlowLiftSettlesBySharedDistanceFromTheRestItLeft() {
		assertEquals(DockRest.Full, dockRest(DockRest.Full, expansion = 0.8f, velocity = 0f))
		assertEquals(DockRest.Bar, dockRest(DockRest.Full, expansion = 0.6f, velocity = 0f))
		assertEquals(DockRest.Bar, dockRest(DockRest.Bar, expansion = 0.2f, velocity = 0f))
		assertEquals(DockRest.Full, dockRest(DockRest.Bar, expansion = 0.4f, velocity = 0f))
	}

	@Test
	fun aFlickSettlesTheWayItPointsWhereverTheFingerLifted() {
		assertEquals(DockRest.Bar, dockRest(DockRest.Full, expansion = 0.95f, velocity = 500f))
		assertEquals(DockRest.Full, dockRest(DockRest.Full, expansion = 0.5f, velocity = -500f))
		assertEquals(DockRest.Full, dockRest(DockRest.Bar, expansion = 0.05f, velocity = -500f))
		assertEquals(DockRest.Bar, dockRest(DockRest.Bar, expansion = 0.6f, velocity = 500f))
	}

	@Test
	fun theBarGoesWhenDraggedFarEnoughOrFlickedAway() {
		assertEquals(0, dockDismiss(offset = 0f, velocity = 0f, width = 400f))
		assertEquals(0, dockDismiss(offset = -50f, velocity = 0f, width = 400f))
		assertEquals(-1, dockDismiss(offset = -170f, velocity = 0f, width = 400f))
		assertEquals(1, dockDismiss(offset = 170f, velocity = 0f, width = 400f))
		assertEquals(1, dockDismiss(offset = 20f, velocity = 600f, width = 400f))
		assertEquals(-1, dockDismiss(offset = -30f, velocity = -600f, width = 400f))
		assertEquals(0, dockDismiss(offset = 20f, velocity = 399f, width = 400f))
	}

	@Test
	fun aFlickBackKeepsTheBarWhateverItsOffset() {
		assertEquals(0, dockDismiss(offset = 200f, velocity = -600f, width = 400f))
		assertEquals(0, dockDismiss(offset = -200f, velocity = 600f, width = 400f))
		assertEquals(0, dockDismiss(offset = 200f, velocity = 0f, width = 0f))
	}

	@Test
	fun thePlayerMovesBetweenClosedFullAndBar() {
		var state = DockState.Closed
		state = state.after(DockEvent.Open)
		assertEquals(DockState.Full, state)
		state = state.after(DockEvent.Minimise)
		assertEquals(DockState.Bar, state)
		state = state.after(DockEvent.Expand)
		assertEquals(DockState.Full, state)
		state = state.after(DockEvent.Minimise).after(DockEvent.Close)
		assertEquals(DockState.Closed, state)
	}

	@Test
	fun anEventThatMeansNothingInAStateLeavesIt() {
		assertEquals(DockState.Closed, DockState.Closed.after(DockEvent.Minimise))
		assertEquals(DockState.Closed, DockState.Closed.after(DockEvent.Expand))
		assertEquals(DockState.Full, DockState.Full.after(DockEvent.Expand))
		assertEquals(DockState.Bar, DockState.Bar.after(DockEvent.Minimise))
	}

	@Test
	fun openingATitleOverTheBarTakesTheFullScreenAndCloseWorksFromEither() {
		assertEquals(DockState.Full, DockState.Bar.after(DockEvent.Open))
		assertEquals(DockState.Closed, DockState.Bar.after(DockEvent.Close))
		assertEquals(DockState.Closed, DockState.Full.after(DockEvent.Close))
		assertEquals(DockState.Closed, DockState.Closed.after(DockEvent.Close))
	}

	@Test
	fun aPhoneTheCurrentPlayerHeldOnItsSideTurnsUprightOnMinimise() {
		val locks = listOf(
			ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
			ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
			ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
			ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
		)
		for (lock in locks) assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientationOnMinimise(lock, phone = true))
	}

	@Test
	fun anOrientationTheScreenWasNotHeldInStaysAsItIs() {
		assertNull(orientationOnMinimise(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, phone = true))
		assertNull(orientationOnMinimise(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, phone = true))
		assertNull(orientationOnMinimise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, phone = true))
		assertNull(orientationOnMinimise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, phone = false))
	}

	@Test
	fun aPhoneIsAScreenNarrowerThan600Dp() {
		assertTrue(isPhone(411))
		assertTrue(isPhone(599))
		assertFalse(isPhone(600))
	}

	@Test
	fun theControlsFadeInOverTheTopOfTheWayUpAndTheBarsTextOutOverTheFirstOfIt() {
		assertEquals(0f, overlayAlpha(0f), 0f)
		assertEquals(0f, overlayAlpha(0.55f), 0f)
		assertEquals(1f, overlayAlpha(0.95f), 0f)
		assertEquals(1f, overlayAlpha(1f), 0f)
		assertEquals(1f, barAlpha(0f), 0f)
		assertEquals(0f, barAlpha(0.4f), 0f)
		assertEquals(0f, barAlpha(1f), 0f)
	}

	@Test
	fun theControlsAndTheBarsTextNeverShowTogetherOnTheWay() {
		for (step in 0..100) {
			val expansion = step / 100f
			assertTrue("at $expansion", overlayAlpha(expansion) == 0f || barAlpha(expansion) == 0f)
		}
	}
}

package com.nphil.marquee.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarSizeTest {
	@Test
	fun theLimitIsAShareOfTheWindowWithABoundOnEachSide() {
		assertEquals(360f, sidebarLimit(1280f), 0f)
		assertEquals(320f, sidebarLimit(800f), 0f)
		assertEquals(OPEN_MIN_DP, sidebarLimit(400f), 0f)
	}

	@Test
	fun aFingerLetGoNarrowOrThrownNarrowLeavesTheRailAndOtherwiseKeepsTheWidth() {
		assertEquals(RAIL_DP, sidebarRest(110f, 0f, 360f), 0f)
		assertEquals(RAIL_DP, sidebarRest(150f, -400f, 360f), 0f)
		assertEquals(300f, sidebarRest(300f, 0f, 360f), 0f)
		// Open, but never narrower than the labels need, and never wider than the window allows.
		assertEquals(OPEN_MIN_DP, sidebarRest(130f, 0f, 360f), 0f)
		assertEquals(OPEN_MIN_DP, sidebarRest(110f, 400f, 360f), 0f)
		assertEquals(360f, sidebarRest(500f, 0f, 360f), 0f)
	}

	@Test
	fun labelsAreHiddenInTheRailAndFullOnceThereIsRoomForThem() {
		assertEquals(0f, sidebarLabels(RAIL_DP), 0f)
		assertEquals(0.5f, sidebarLabels(126f), 0.001f)
		assertEquals(1f, sidebarLabels(OPEN_DP), 0f)
	}

	@Test
	fun aDragMovesFromWhereTheFingerTookHoldAndStaysBetweenTheRailAndTheLimit() {
		val sidebar = SidebarSize(expanded = true, open = OPEN_DP)
		sidebar.fit(windowPx = 1000, density = 1f, inset = 0f)
		assertFalse(sidebar.moving)

		sidebar.dragStarted()
		assertTrue(sidebar.moving)
		sidebar.dragBy(40f)
		assertEquals(OPEN_DP + 40f, sidebar.width, 0f)
		sidebar.dragBy(-40f)
		assertEquals(OPEN_DP - 40f, sidebar.width, 0f)
		sidebar.dragBy(500f)
		assertEquals(sidebarLimit(1000f), sidebar.width, 0f)
		sidebar.dragBy(-500f)
		assertEquals(RAIL_DP, sidebar.width, 0f)
	}

	@Test
	fun aWidthTheWindowCannotHoldIsShownNarrowerWithoutForgettingIt() {
		val sidebar = SidebarSize(expanded = true, open = 360f)
		sidebar.fit(windowPx = 800, density = 1f, inset = 0f)
		assertEquals(320f, sidebar.width, 0f)
		assertEquals(360f, sidebar.open, 0f)
	}

	@Test
	fun theWidthInPxIncludesTheStartInset() {
		val sidebar = SidebarSize(expanded = true, open = OPEN_DP)
		sidebar.fit(windowPx = 2000, density = 2f, inset = 24f)
		assertEquals(OPEN_DP * 2 + 24f, sidebar.widthPx, 0f)
	}

	@Test
	fun jumpingToTheWidthItHasLandsAtOnceAndRemembersItOpen() {
		val sidebar = SidebarSize(expanded = false, open = 300f)
		sidebar.fit(windowPx = 2000, density = 1f, inset = 0f)
		sidebar.jumpTo(CoroutineScope(Job()), RAIL_DP)
		assertFalse(sidebar.expanded)
		assertEquals(300f, sidebar.open, 0f)
		assertFalse(sidebar.moving)
	}

	@Test
	fun theChoiceSurvivesARecreatedActivity() {
		val saved = with(SidebarSize.Saver) { SaverScope { true }.save(SidebarSize(expanded = false, open = 300f)) }
		val restored = SidebarSize.Saver.restore(saved!!)!!
		assertFalse(restored.expanded)
		assertEquals(300f, restored.open, 0f)
	}
}

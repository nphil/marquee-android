package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteDockTest {
	private val field = Rect(16f, 16f, 1016f, 816f)
	private val size = 56f

	@Test
	fun docksSurviveSavingAndBadValuesAreRejected() {
		val docks = listOf(RemoteDock.Sidebar) + DockSide.entries.flatMap { side -> (0 until DockRows).map { RemoteDock.Edge(side, it) } }
		for (dock in docks) assertEquals(dock, decodedDock(dock.encoded()))
		for (text in listOf(null, "", "middle:2", "end", "end:9", "start:-1", "end:x")) assertNull(text, decodedDock(text))
	}

	@Test
	fun edgeSpotsRunDownTheTwoEdges() {
		assertEquals(Offset(16f, 16f), edgeOrigin(field, RemoteDock.Edge(DockSide.Start, 0), size))
		assertEquals(Offset(1016f - size, 816f - size), edgeOrigin(field, RemoteDock.Edge(DockSide.End, DockRows - 1), size))
		assertEquals(16f + (800f - size) / 2, edgeOrigin(field, RemoteDock.Edge(DockSide.End, 2), size).y, 0.01f)
	}

	@Test
	fun aDropLandsOnTheNearestSpot() {
		assertEquals(RemoteDock.Edge(DockSide.Start, DockRows - 1), nearestDock(Offset(100f, 790f), field, size, null, 96f))
		assertEquals(RemoteDock.Edge(DockSide.End, 0), nearestDock(Offset(900f, 44f), field, size, null, 96f))
		assertEquals(RemoteDock.Edge(DockSide.End, 2), nearestDock(Offset(900f, 416f), field, size, null, 96f))
	}

	@Test
	fun theSidebarSpotCapturesADropNearIt() {
		val sidebar = Offset(200f, 760f)
		assertEquals(RemoteDock.Sidebar, nearestDock(Offset(250f, 730f), field, size, sidebar, 96f))
		assertEquals(RemoteDock.Edge(DockSide.Start, DockRows - 1), nearestDock(Offset(40f, 790f), field, size, sidebar, 96f))
	}

	@Test
	fun aFlingProjectsWhereTheOrbWouldRest() {
		val rest = projectedCenter(Offset(100f, 100f), Offset(1000f, -500f))
		assertEquals(280f, rest.x, 0.01f)
		assertEquals(10f, rest.y, 0.01f)
	}

	@Test
	fun theOrbSitsInlineInAWideSidebarAndAboveTheAvatarInANarrowOne() {
		val profile = Rect(16f, 700f, 64f, 748f)
		val inline = sidebarOrigin(240f, profile, 48f, 16f, 8f, 1f)
		assertEquals(176f, inline.x, 0.01f)
		assertEquals(700f, inline.y, 0.01f)
		val stacked = sidebarOrigin(72f, profile, 48f, 16f, 8f, 0f)
		assertEquals(16f, stacked.x, 0.01f)
		assertEquals(644f, stacked.y, 0.01f)
		val between = sidebarOrigin(240f, profile, 48f, 16f, 8f, 0.5f)
		assertEquals(96f, between.x, 0.01f)
		assertEquals(672f, between.y, 0.01f)
	}

	@Test
	fun theSidebarKeepsRoomOnlyWhileTheOrbIsStacked() {
		assertEquals(56f, dockRoom(0f, 56f), 0f)
		assertEquals(42f, dockRoom(0.25f, 56f), 0.01f)
		assertEquals(0f, dockRoom(1f, 56f), 0f)
	}
}

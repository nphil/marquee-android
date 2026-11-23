package com.nphil.marquee.ui.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDimmingTest {
	private val edge = RemoteDock.Edge(DockSide.End, 2)

	@Test
	fun theOrbDimsOnceItHasRestedAtAnEdgeForTheDwell() {
		assertTrue(orbDimmed(edge, panelOpen = false, idleMs = 4_000L))
		assertFalse(orbDimmed(edge, panelOpen = false, idleMs = 3_999L))
	}

	@Test
	fun anOpenPanelNeverDimsTheOrb() {
		assertFalse(orbDimmed(edge, panelOpen = true, idleMs = 60_000L))
	}

	@Test
	fun theSidebarOrbNeverDims() {
		assertFalse(orbDimmed(RemoteDock.Sidebar, panelOpen = false, idleMs = 60_000L))
	}

	@Test
	fun theDwellAndDimmedAlphaAreFixed() {
		assertEquals(4_000L, DimDwellMs)
		assertEquals(0.4f, DimmedAlpha, 0f)
	}
}

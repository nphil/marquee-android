package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePanelGeometryTest {
	private val bounds = Rect(8f, 8f, 808f, 608f)
	private val panel = Rect(100f, 100f, 400f, 500f)

	@Test
	fun aMoveStopsAtTheBounds() {
		assertEquals(Rect(110f, 120f, 410f, 520f), movePanel(panel, Offset(10f, 20f), bounds))
		assertEquals(Rect(508f, 100f, 808f, 500f), movePanel(panel, Offset(1000f, 0f), bounds))
		assertEquals(Rect(8f, 8f, 308f, 408f), movePanel(panel, Offset(-1000f, -1000f), bounds))
	}

	@Test
	fun aResizeMovesOnlyTheHandlesEdgesAndKeepsTheMinimum() {
		val topLeft = PanelHandle(left = true, top = true)
		assertEquals(Rect(8f, 8f, 400f, 500f), resizePanel(panel, topLeft, Offset(-500f, -500f), bounds, 232f))
		assertEquals(Rect(168f, 268f, 400f, 500f), resizePanel(panel, topLeft, Offset(500f, 500f), bounds, 232f))
		val bottomRight = PanelHandle(right = true, bottom = true)
		assertEquals(Rect(100f, 100f, 808f, 608f), resizePanel(panel, bottomRight, Offset(900f, 900f), bounds, 232f))
		assertEquals(Rect(100f, 100f, 332f, 332f), resizePanel(panel, bottomRight, Offset(-900f, -900f), bounds, 232f))
		assertEquals(Rect(100f, 100f, 400f, 540f), resizePanel(panel, PanelHandle(bottom = true), Offset(40f, 40f), bounds, 232f))
	}

	@Test
	fun theEightHandlesCoverEveryEdgeAndCorner() {
		assertEquals(8, PanelHandle.All.distinct().size)
		assertEquals(4, PanelHandle.All.count { listOf(it.left, it.top, it.right, it.bottom).count { edge -> edge } == 2 })
	}

	@Test
	fun theOpeningPanelIsCentredAtTheTopAndNeverLargerThanTheBounds() {
		assertEquals(Rect(218f, 8f, 598f, 608f), defaultPanel(bounds, 380f, 660f))
		assertEquals(Rect(218f, 8f, 598f, 296f), defaultPanel(bounds, 380f, 288f))
	}

	@Test
	fun fitPanelPullsARectBackInsideAndGrowsATinyOneToTheMinimum() {
		assertEquals(Rect(428f, 100f, 808f, 500f), fitPanel(Rect(600f, 100f, 980f, 500f), bounds, 232f))
		assertEquals(Rect(100f, 100f, 332f, 332f), fitPanel(Rect(100f, 100f, 150f, 150f), bounds, 232f))
		assertEquals(bounds, fitPanel(Rect(0f, 0f, 2000f, 2000f), bounds, 232f))
	}

	@Test
	fun snappingKeepsEveryEdgeOnTheGridAndTheRectNonEmpty() {
		assertEquals(Rect(100f, 104f, 400f, 500f), snapPanel(Rect(101f, 103f, 399f, 501f), 4f))
		assertEquals(Rect(100f, 100f, 104f, 104f), snapPanel(Rect(100f, 100f, 101f, 101f), 4f))
	}

	@Test
	fun savedFractionsFollowTheWindowAndRoundTripThroughText() {
		val fractions = panel.toFractions(bounds)
		val back = fractions.toRect(bounds)
		assertEquals(panel.left, back.left, 0.01f)
		assertEquals(panel.bottom, back.bottom, 0.01f)
		val decoded = decodedPanel(fractions.encoded())
		assertNotNull(decoded)
		assertEquals(fractions.x, decoded!!.x, 1e-4f)
		assertEquals(fractions.h, decoded.h, 1e-4f)
		val rotated = fractions.toRect(Rect(0f, 0f, 600f, 800f))
		assertEquals(fractions.w * 600f, rotated.width, 0.01f)
		for (text in listOf(null, "", "0.1,0.2,0.3", "a,b,c,d", "0.1,0.1,0,0.5")) assertNull(text, decodedPanel(text))
	}
}

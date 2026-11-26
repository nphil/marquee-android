package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLayoutTest {
	private fun layout(width: Float, height: Float, visible: Set<RemoteModule> = AllModules, tray: Float = 0f) =
		remoteLayout(width, height, visible, tray)

	private fun RemoteLayout.shown() = strips.map { it.strip }

	@Test
	fun aTallPanelStacksTheStripsBelowThePad() {
		val layout = layout(380f, 660f)
		assertEquals(RemoteShape.Column, layout.shape)
		assertEquals(RemoteStrip.entries, layout.shown())
		assertEquals(layout.pad.bottom + RemoteMetrics.Gap, layout.strips.first().rect.top, 0f)
		assertTrue(layout.dropped.isEmpty())
	}

	@Test
	fun aWidePanelPutsTheStripsBesideThePadInColumns() {
		val layout = layout(700f, 340f)
		assertEquals(RemoteShape.Row, layout.shape)
		assertEquals(RemoteStrip.entries, layout.shown())
		assertTrue(layout.strips.all { it.rect.left >= layout.pad.right + RemoteMetrics.Gap })
		assertEquals(2, layout.strips.map { it.rect.left }.distinct().size)
	}

	@Test
	fun aSmallPanelShowsOnlyThePad() {
		val layout = layout(232f, 232f)
		assertEquals(RemoteShape.Compact, layout.shape)
		assertEquals(layout.area, layout.pad)
		assertTrue(layout.strips.isEmpty())
		assertEquals(RemoteModule.entries - RemoteModule.Pad, layout.dropped)
	}

	@Test
	fun theShapeFollowsTheWorkingAreaAtItsThresholds() {
		// A column needs 212 dp below the header: the pad's minimum, a gap and one row of keys.
		assertEquals(RemoteShape.Compact, layout(300f, 280f).shape)
		assertEquals(RemoteShape.Column, layout(300f, 284f).shape)
		// A row needs the area 1.25 times wider than tall, and a pad beside a strip.
		assertEquals(RemoteShape.Row, layout(400f, 360f).shape)
		assertEquals(RemoteShape.Column, layout(400f, 364f).shape)
	}

	@Test
	fun stripsLeaveLeastUsedFirstAsThePanelGetsShorter() {
		val expected = listOf(
			560f to listOf(RemoteStrip.Nav, RemoteStrip.Volume, RemoteStrip.Channel, RemoteStrip.Playback, RemoteStrip.Launchers),
			492f to listOf(RemoteStrip.Nav, RemoteStrip.Volume, RemoteStrip.Playback, RemoteStrip.Launchers),
			424f to listOf(RemoteStrip.Nav, RemoteStrip.Volume, RemoteStrip.Launchers),
			356f to listOf(RemoteStrip.Nav, RemoteStrip.Volume),
			288f to listOf(RemoteStrip.Nav),
		)
		for ((height, strips) in expected) assertEquals("height $height", strips, layout(300f, height).shown())
		assertEquals(listOf(RemoteModule.Channel, RemoteModule.Playback), layout(300f, 424f).dropped)
	}

	@Test
	fun onlyTheChosenModulesAreLaidOut() {
		val layout = layout(380f, 660f, setOf(RemoteModule.Pad, RemoteModule.Keyboard, RemoteModule.More))
		val launchers = layout.strips.single()
		assertEquals(RemoteStrip.Launchers, launchers.strip)
		assertEquals(listOf(RemoteModule.Keyboard, RemoteModule.More), launchers.modules)
		assertEquals(2, launchers.keyRects.size)
		assertEquals(launchers.modules, launchers.moduleRects.map { it.first })
		assertTrue(layout.dropped.isEmpty())
		assertEquals(layout.area, layout(380f, 660f, setOf(RemoteModule.Pad)).pad)
	}

	@Test
	fun modulesThatDoNotFitAreReportedAsDropped() {
		val layout = layout(232f, 232f, setOf(RemoteModule.Pad, RemoteModule.Volume))
		assertEquals(listOf(RemoteModule.Volume), layout.dropped)
	}

	@Test
	fun theTrayTakesRoomFromTheBottom() {
		val plain = layout(380f, 660f)
		val tray = layout(380f, 660f, tray = RemoteMetrics.Tray)
		assertEquals(plain.area.bottom - RemoteMetrics.Tray, tray.area.bottom, 0f)
	}

	@Test
	fun everythingStaysInsideTheAreaOnTheGridWithoutOverlap() {
		for (width in 232..800 step 28) for (height in 232..900 step 36) for (tray in listOf(0f, RemoteMetrics.Tray)) {
			val layout = layout(width.toFloat(), height.toFloat(), tray = tray)
			val label = "${width}x$height tray $tray"
			val placed = listOf(layout.pad) + layout.strips.map { it.rect }
			for (rect in placed) {
				assertTrue(label, rect.left >= layout.area.left && rect.top >= layout.area.top)
				assertTrue(label, rect.right <= layout.area.right && rect.bottom <= layout.area.bottom)
			}
			for (strip in layout.strips) {
				for (edge in listOf(strip.rect.left, strip.rect.top, strip.rect.right, strip.rect.bottom)) {
					assertEquals(label, 0f, edge % RemoteMetrics.Grid, 0f)
				}
			}
			for (a in placed.indices) for (b in a + 1 until placed.size) assertFalse(label, placed[a].overlaps(placed[b]))
		}
	}

	@Test
	fun splitDividesARectIntoKeysWithGaps() {
		val keys = Rect(0f, 0f, 192f, 56f).split(3, 12f)
		assertEquals(listOf(Rect(0f, 0f, 56f, 56f), Rect(68f, 0f, 124f, 56f), Rect(136f, 0f, 192f, 56f)), keys)
		assertTrue(Rect(0f, 0f, 192f, 56f).split(0, 12f).isEmpty())
	}

	@Test
	fun savedModulesRoundTripAndAlwaysKeepThePad() {
		assertEquals(AllModules, decodeModules(null))
		assertEquals(setOf(RemoteModule.Pad), decodeModules(""))
		assertEquals(setOf(RemoteModule.Pad, RemoteModule.Nav, RemoteModule.Volume), decodeModules("volume,nav,bogus"))
		assertEquals("nav,volume", encodeModules(setOf(RemoteModule.Volume, RemoteModule.Pad, RemoteModule.Nav)))
		assertEquals(AllModules, decodeModules(encodeModules(AllModules)))
	}
}

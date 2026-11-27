package com.nphil.marquee.bigscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigScreenTest {
	private val tv = DisplayIdentity(3840, 2160, 60)

	@Test
	fun shelvesKeepEachRowsPlaceAndAGridCarriesTheColumn() {
		val shelves = FocusGrid(listOf(5, 5, 2))
		repeat(3) { shelves.move(FocusMove.Right) }
		shelves.move(FocusMove.Down)
		assertEquals(0, shelves.column)
		shelves.move(FocusMove.Up)
		assertEquals(3, shelves.column)

		val grid = FocusGrid(listOf(5, 5, 2), FocusGrid.Style.Grid)
		repeat(3) { grid.move(FocusMove.Right) }
		grid.move(FocusMove.Down)
		grid.move(FocusMove.Down)
		assertEquals(1, grid.column)
		grid.move(FocusMove.Up)
		assertEquals(3, grid.column)
		assertEquals(FocusGrid.Outcome.Edge, FocusGrid(listOf(2)).move(FocusMove.Left))
	}

	@Test
	fun aRememberedAnswerBeatsThePagesChoice() {
		val memory = DisplayMemory().remembering(tv, DisplayKind.Tv, BigScreenUse.Mirror)
		assertEquals(DisplayOutcome.Mirror, DisplayOutcome.decide(tv, memory, BigScreenUse.Marquee, DisplayKind.Glasses))
		assertEquals(DisplayOutcome.Ask, DisplayOutcome.decide(tv, DisplayMemory(), BigScreenUse.Ask, DisplayKind.Glasses))
		assertEquals(DisplayOutcome.Marquee(DisplayKind.Glasses), DisplayOutcome.decide(tv, DisplayMemory(), BigScreenUse.Marquee, DisplayKind.Glasses))
		assertTrue(DisplayMemory().remembering(tv, DisplayKind.Tv, BigScreenUse.Ask).displays.isEmpty())
		assertTrue(memory.forgetting(tv.key).displays.isEmpty())
	}

	@Test
	fun aSlowDragStepsOnceAcrossAndAHardFlickCoasts() {
		val swipe = SwipeSteps()
		assertTrue(swipe.moved(5.0, 0.0).isEmpty())
		assertEquals(listOf(FocusMove.Right, FocusMove.Right), swipe.moved(110.0, 3.0))
		assertEquals(listOf(FocusMove.Right), swipe.moved(165.0, 3.0))
		val flick = SwipeSteps().also { it.moved(14.0, 0.0) }.released(1500.0, 0.0)
		assertEquals(FocusMove.Right, flick.step)
		assertEquals(FocusMove.Right, flick.coastMove)
		assertNull(SwipeSteps().released(100.0, 0.0).step)
		val times = SwipeSteps.coast(1500.0, 52.0)
		assertTrue(times.isNotEmpty() && times.zipWithNext().all { (a, b) -> b > a })
		assertTrue(SwipeSteps.coast(500.0, 52.0).isEmpty())
	}

	@Test
	fun theKeyboardTypesAndDeletes() {
		assertEquals("a ", GlassesKeyboard.type(GlassesKey.Space, "a"))
		assertEquals("a ", GlassesKeyboard.type(GlassesKey.Space, "a "))
		assertEquals("", GlassesKeyboard.type(GlassesKey.Space, ""))
		assertEquals("ab", GlassesKeyboard.type(GlassesKey.Character('b'), "a"))
		assertEquals("a", GlassesKeyboard.type(GlassesKey.Delete, "ab"))
	}

	@Test
	fun tvSearchKeepsRecentsUntilTwoCharactersHaveBeenEntered() {
		assertEquals("", GlassesKeyboard.tvSearchTerm(" a "))
		assertEquals("", GlassesKeyboard.tvSearchTerm("🎬"))
		assertEquals("al", GlassesKeyboard.tvSearchTerm(" al "))
	}

	@Test
	fun scrubbingStaysInsideTheVideoAndClicksEveryTenSeconds() {
		val scrub = Scrub(at = 100.0, duration = 3600.0)
		assertEquals(Scrub.Tick.Detent, scrub.slid(60.0, 100.0))
		scrub.slid(-1000.0, 100.0)
		assertEquals(0.0, scrub.time, 0.0)
	}
}

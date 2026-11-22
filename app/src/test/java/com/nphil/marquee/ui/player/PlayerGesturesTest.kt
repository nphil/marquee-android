package com.nphil.marquee.ui.player

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import com.nphil.marquee.core.Chapter
import com.nphil.marquee.core.Marker
import com.nphil.marquee.playback.SkipBurst
import com.nphil.marquee.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerGesturesTest {
	@Test
	fun zonesSplitTheWidthIntoThirds() {
		assertEquals(Zone.Start, zoneOf(0f, 900f))
		assertEquals(Zone.Start, zoneOf(299f, 900f))
		assertEquals(Zone.Middle, zoneOf(300f, 900f))
		assertEquals(Zone.Middle, zoneOf(599f, 900f))
		assertEquals(Zone.End, zoneOf(600f, 900f))
		assertEquals(Zone.End, zoneOf(900f, 900f))
	}

	@Test
	fun zoneSpansTileTheWidth() {
		val start = zoneSpan(Zone.Start, 900f)
		val middle = zoneSpan(Zone.Middle, 900f)
		val end = zoneSpan(Zone.End, 900f)
		assertEquals(0f, start.start, 0f)
		assertEquals(300f, start.endInclusive, 0f)
		assertEquals(start.endInclusive, middle.start, 0f)
		assertEquals(600f, middle.endInclusive, 0f)
		assertEquals(middle.endInclusive, end.start, 0f)
		assertEquals(900f, end.endInclusive, 0f)
	}

	@Test
	fun theLeftHalfSetsBrightnessAndTheRightHalfVolume() {
		assertEquals(SwipeKind.Brightness, swipeKind(0f, 1000f))
		assertEquals(SwipeKind.Brightness, swipeKind(499f, 1000f))
		assertEquals(SwipeKind.Volume, swipeKind(500f, 1000f))
		assertEquals(SwipeKind.Volume, swipeKind(1000f, 1000f))
	}

	@Test
	fun swipingUpRaisesTheLevelAndDownLowersIt() {
		// 75% of the height is a full sweep: 300 px on a 400 px picture.
		assertEquals(0.75f, swipeLevel(0.5f, -75f, 400f), 1e-6f)
		assertEquals(0.25f, swipeLevel(0.5f, 75f, 400f), 1e-6f)
		assertEquals(0.5f, swipeLevel(0.5f, 0f, 400f), 1e-6f)
	}

	@Test
	fun theLevelStaysBetweenNothingAndFull() {
		assertEquals(1f, swipeLevel(0.5f, -150f, 400f), 0f)
		assertEquals(1f, swipeLevel(0.9f, -1000f, 400f), 0f)
		assertEquals(0f, swipeLevel(0.5f, 1000f, 400f), 0f)
		assertEquals(0.3f, swipeLevel(0.3f, 10f, 0f), 0f)
	}

	@Test
	fun volumeStepRoundsToTheDevicesSteps() {
		assertEquals(0, volumeStep(0f, 15))
		assertEquals(8, volumeStep(0.5f, 15))
		assertEquals(15, volumeStep(1f, 15))
		assertEquals(15, volumeStep(2f, 15))
		assertEquals(0, volumeStep(-1f, 15))
	}

	@Test
	fun theSkipSideFollowsTheTotal() {
		assertEquals(SkipSide.Forward, skipSide(0, 10, SkipSide.Back))
		assertEquals(SkipSide.Back, skipSide(10, -10, SkipSide.Forward))
		assertEquals(SkipSide.Back, skipSide(10, 10, SkipSide.Back))
		assertEquals(SkipSide.Forward, skipSide(10, 10, SkipSide.Forward))
	}

	@Test
	fun theSkipLabelUsesARealMinusSign() {
		assertEquals("+10 s", skipLabel(10))
		assertEquals("\u221220 s", skipLabel(-20))
		assertEquals("0 s", skipLabel(0))
	}

	@Test
	fun aRunOfTapsGivesTheRippleItsSideAndTotal() {
		val burst = SkipBurst()
		val taps = listOf(10 to 0.0, 10 to 0.3, 10 to 0.6, -10 to 0.9, -10 to 3.0)
		var total = 0
		var side = SkipSide.Forward
		val shown = taps.map { (by, now) ->
			val skip = burst.tap(by, position = 100.0, now = now)
			side = skipSide(total, skip.total, side)
			total = skip.total
			Triple(skipLabel(skip.total), side, skip.target)
		}
		assertEquals(
			listOf(
				Triple("+10 s", SkipSide.Forward, 110.0),
				Triple("+20 s", SkipSide.Forward, 120.0),
				Triple("+30 s", SkipSide.Forward, 130.0),
				Triple("+20 s", SkipSide.Back, 120.0),
				// A pause past the window starts a new run from where the title is.
				Triple("\u221210 s", SkipSide.Back, 90.0),
			),
			shown,
		)
	}

	@Test
	fun pinchingOutFillsAndInFits() {
		assertEquals(PlayerAspect.Fill, pinchAspect(1.12f))
		assertEquals(PlayerAspect.Fill, pinchAspect(1.6f))
		assertEquals(PlayerAspect.Fit, pinchAspect(0.9f))
		assertEquals(PlayerAspect.Fit, pinchAspect(0.4f))
	}

	@Test
	fun aSmallPinchLeavesThePictureAlone() {
		assertNull(pinchAspect(1f))
		assertNull(pinchAspect(1.11f))
		assertNull(pinchAspect(0.91f))
	}

	@Test
	fun draggingAcrossThePictureMovesThePlayhead() {
		assertEquals(140.0, scrubbed(100.0, 300f, 900f, 120.0, 3600.0), 1e-4)
		assertEquals(60.0, scrubbed(100.0, -300f, 900f, 120.0, 3600.0), 1e-4)
		assertEquals(0.0, scrubbed(10.0, -900f, 900f, 120.0, 3600.0), 0.0)
		assertEquals(3600.0, scrubbed(3590.0, 900f, 900f, 120.0, 3600.0), 0.0)
		assertEquals(100.0, scrubbed(100.0, 300f, 0f, 120.0, 3600.0), 0.0)
	}

	@Test
	fun aFullWidthDragCoversTwoMinutesAtMost() {
		assertEquals(120.0, pictureSpan(3600.0), 0.0)
		assertEquals(90.0, pictureSpan(90.0), 0.0)
	}

	@Test
	fun theScrubGetsFinerAsTheFingerMovesAwayFromTheBar() {
		assertEquals(ScrubPace.Full, ScrubPace.at(0f))
		assertEquals(ScrubPace.Full, ScrubPace.at(43.9f))
		assertEquals(ScrubPace.Half, ScrubPace.at(44f))
		assertEquals(ScrubPace.Half, ScrubPace.at(89.9f))
		assertEquals(ScrubPace.Quarter, ScrubPace.at(90f))
		assertEquals(ScrubPace.Quarter, ScrubPace.at(149.9f))
		assertEquals(ScrubPace.Fine, ScrubPace.at(150f))
		assertEquals(ScrubPace.Fine, ScrubPace.at(1000f))
		assertNull(ScrubPace.Full.label)
		assertNotNull(ScrubPace.Fine.label)
	}

	@Test
	fun aScrubReportsWhereItBeganMovedAndEnded() {
		val log = mutableListOf<String>()
		val scrub = ScrubState(began = { log += "began" }, moved = { log += "moved $it" }, ended = { log += "ended $it" })
		scrub.moveTo(5.0)
		assertFalse(scrub.active)

		scrub.begin(10.0)
		assertTrue(scrub.active)
		scrub.moveTo(25.0)
		scrub.finish()
		scrub.finish()
		assertFalse(scrub.active)
		assertEquals(listOf("began", "moved 10.0", "moved 25.0", "ended 25.0"), log)
	}

	@Test
	fun theBarIsCutAtEachEdgeInsideTheTitle() {
		assertEquals(listOf(0.25f, 0.5f), seekCuts(1000L, listOf(0L, 250L, 500L, 500L, 1000L, 2000L)))
		assertEquals(listOf(0.25f, 0.5f), seekCuts(1000L, listOf(500L, 250L)))
		assertEquals(emptyList<Float>(), seekCuts(0L, listOf(500L)))
	}

	@Test
	fun theMarksAreBandsOfTheBar() {
		val bands = seekBands(1000L, listOf(100L..200L, 900L..1500L, 300L..300L))
		assertEquals(2, bands.size)
		assertEquals(0.1f, bands[0].start, 1e-6f)
		assertEquals(0.2f, bands[0].endInclusive, 1e-6f)
		assertEquals(0.9f, bands[1].start, 1e-6f)
		assertEquals(1f, bands[1].endInclusive, 0f)
		assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), seekBands(0L, listOf(0L..10L)))
	}

	@Test
	fun cutsComeFromChaptersAndTheEdgesOfTheMarks() {
		val chapters = listOf(Chapter(title = "One", start = 0, end = 60_000), Chapter(start = 60_000, end = 120_000))
		val spans = listOf(Marker(5_000, 35_000), Marker(110_000, 120_000, isFinal = true))
		assertEquals(listOf(0L, 60_000L, 5_000L, 35_000L, 110_000L, 120_000L), cutEdges(chapters, spans))
		assertEquals(listOf(5_000L..35_000L, 110_000L..120_000L), markRanges(spans))
		assertEquals(emptyList<Long>(), cutEdges(emptyList(), emptyList()))
	}

	@Test
	fun speedsReadAsTheControlsWriteThem() {
		assertEquals("1\u00D7", speedLabel(1f))
		assertEquals("2\u00D7", speedLabel(2f))
		assertEquals("1.5\u00D7", speedLabel(1.5f))
		assertEquals("0.75\u00D7", speedLabel(0.75f))
		assertEquals("Normal", speedTitle(1f))
		assertEquals("1.5\u00D7", speedTitle(1.5f))
	}

	@Test
	fun theTransportKeysWorkWhereverTheFocusIs() {
		for (onPicture in listOf(true, false)) {
			assertEquals(KeyAction.Toggle, keyAction(Key.Spacebar, onPicture))
			assertEquals(KeyAction.Toggle, keyAction(Key.K, onPicture))
			assertEquals(KeyAction.Toggle, keyAction(Key.MediaPlayPause, onPicture))
			assertEquals(KeyAction.Play, keyAction(Key.MediaPlay, onPicture))
			assertEquals(KeyAction.Pause, keyAction(Key.MediaPause, onPicture))
			assertEquals(KeyAction.Back, keyAction(Key.J, onPicture))
			assertEquals(KeyAction.Back, keyAction(Key.ButtonL1, onPicture))
			assertEquals(KeyAction.Forward, keyAction(Key.L, onPicture))
			assertEquals(KeyAction.Forward, keyAction(Key.ButtonR1, onPicture))
			assertEquals(KeyAction.Aspect, keyAction(Key.F, onPicture))
			assertEquals(KeyAction.Mute, keyAction(Key.M, onPicture))
			assertEquals(KeyAction.Next, keyAction(Key.MediaNext, onPicture))
		}
	}

	@Test
	fun theArrowsAreShortcutsOnlyWhileThePictureHasTheFocus() {
		assertEquals(KeyAction.Back, keyAction(Key.DirectionLeft, true))
		assertEquals(KeyAction.Forward, keyAction(Key.DirectionRight, true))
		assertEquals(KeyAction.VolumeUp, keyAction(Key.DirectionUp, true))
		assertEquals(KeyAction.VolumeDown, keyAction(Key.DirectionDown, true))
		assertEquals(KeyAction.Toggle, keyAction(Key.Enter, true))
		assertEquals(KeyAction.Toggle, keyAction(Key.ButtonA, true))
		for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA)) {
			assertNull(keyAction(key, false))
		}
	}

	@Test
	fun otherKeysAreNotTheirs() {
		assertNull(keyAction(Key.Escape, true))
		assertNull(keyAction(Key.A, true))
		assertTrue(KeyAction.VolumeUp.repeats && KeyAction.VolumeDown.repeats)
		assertFalse(KeyAction.Toggle.repeats || KeyAction.Back.repeats || KeyAction.Forward.repeats)
	}

	@Test
	fun aFinishedScrubStaysOnTheBarUntilThePlayerArrives() {
		val hold = PositionHold()
		assertEquals(10_000L, hold.shown(10_000L, 0L))

		hold.hold(60_000L, 0L)
		assertEquals(60_000L, hold.shown(10_000L, 1_000L))
		assertEquals(58_500L, hold.shown(58_500L, 2_000L))
		assertEquals(10_000L, hold.shown(10_000L, 3_000L))
	}

	@Test
	fun aScrubThePlayerNeverReachesIsLetGoAfterEightSeconds() {
		val hold = PositionHold()
		hold.hold(60_000L, 0L)
		assertEquals(60_000L, hold.shown(10_000L, 8_000L))
		assertEquals(10_000L, hold.shown(10_000L, 8_001L))
	}

	@Test
	fun aReleasedHoldShowsThePlayer() {
		val hold = PositionHold()
		hold.hold(60_000L, 0L)
		hold.release()
		assertEquals(10_000L, hold.shown(10_000L, 1L))
	}

	@Test
	fun theSleepTimerCountsDownOnTheClockItIsGiven() {
		val timer = SleepTimer()
		assertFalse(timer.set)
		assertEquals(0L, timer.remainingMs(now = 5L))

		timer.start(15, now = 1_000L)
		assertTrue(timer.set)
		assertEquals(15, timer.minutes)
		assertEquals(900_000L, timer.remainingMs(now = 1_000L))
		assertEquals(15, timer.minutesLeft(now = 1_001L))
		assertEquals(1, timer.minutesLeft(now = 1_000L + 880_000L))
		assertEquals(0, timer.minutesLeft(now = 1_000L + 900_000L))
		assertEquals(0L, timer.remainingMs(now = 1_000L + 2_000_000L))

		timer.cancel()
		assertFalse(timer.set)
		assertEquals(0, timer.minutes)
	}

	@Test
	fun theSleepTimerSurvivesARotation() {
		val timer = SleepTimer()
		timer.start(30, now = 2_000L)
		val saved = with(SleepTimer.Saver) { SaverScope { true }.save(timer) }
		val restored = SleepTimer.Saver.restore(saved!!)!!
		assertTrue(restored.set)
		assertEquals(30, restored.minutes)
		assertEquals(timer.deadline, restored.deadline)

		val off = SleepTimer.Saver.restore(with(SleepTimer.Saver) { SaverScope { true }.save(SleepTimer()) }!!)!!
		assertFalse(off.set)
	}

	@Test
	fun theAccentIsLiftedOnlyWhereItWouldNotReadOnBlack() {
		val light = Color(0xFFE0DEF4)
		assertEquals(light.toArgb(), playerAccent(light).toArgb())

		for (dim in listOf(Color(0xFF1A237E), Color(0xFF000000), Color(0xFF575279))) {
			val lifted = playerAccent(dim)
			assertNotEquals(dim, lifted)
			assertTrue("$dim lifted to $lifted", contrastOnBlack(lifted) >= 6.99f)
		}
	}

	@Test
	fun everyThemesAccentReadsOnThePlayersDarkShades() {
		val catalog = ThemeCatalog.parse(File(System.getProperty("marquee.themes")!!).readText())
		for (theme in catalog.themes) {
			val lifted = playerAccent(theme.accent)
			assertTrue("${theme.id}: $lifted", contrastOnBlack(lifted) >= 6.99f)
		}
	}

	private fun contrastOnBlack(color: Color): Float = (color.luminance() + 0.05f) / 0.05f
}

package com.nphil.marquee.playback

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancePlanTest {
	private val sd = PixelSize(1280, 720)
	private val screen = PixelSize(2560, 1440)
	private val automatic = PictureEnhance(EnhanceUpscaler.Automatic)
	private val ready = EnhanceFacts(source = sd, display = screen)

	private fun running(state: EnhanceState) = (state as EnhanceState.Running).plan

	@Test
	fun offDoesNothingWhateverElseIsTrue() {
		val facts = ready.copy(hdr = true, blocker = EnhanceUnavailable.NoGpu, powerShed = true)
		assertEquals(EnhanceState.Idle("off"), decideEnhance(PictureEnhance(), facts, 0, overloaded = true))
	}

	@Test
	fun aBlockerAndHdrMakeItUnavailableBeforeAnythingElse() {
		val blocked = ready.copy(blocker = EnhanceUnavailable.Tunnelling, hdr = true, powerShed = true, source = null)
		assertEquals(EnhanceState.Unavailable(EnhanceUnavailable.Tunnelling), decideEnhance(automatic, blocked, 0, true))
		val hdr = blocked.copy(blocker = null)
		assertEquals(EnhanceState.Unavailable(EnhanceUnavailable.HighDynamicRange), decideEnhance(automatic, hdr, 0, true))
	}

	@Test
	fun noPictureYetWaitsAndATinyOneIsRefused() {
		assertEquals(EnhanceState.Idle("waiting"), decideEnhance(automatic, ready.copy(source = null), 0, false))
		val tiny = ready.copy(source = PixelSize(1, 720))
		assertEquals(EnhanceState.Unavailable(EnhanceUnavailable.UnsupportedPicture), decideEnhance(automatic, tiny, 0, false))
	}

	@Test
	fun powerSavingPausesBeforeDroppedFramesDo() {
		val hot = ready.copy(powerShed = true)
		assertEquals(EnhanceState.Paused(EnhancePause.PowerSaving), decideEnhance(automatic, hot, 0, overloaded = true))
		assertEquals(EnhanceState.Paused(EnhancePause.DroppedFrames), decideEnhance(automatic, ready, 0, overloaded = true))
	}

	@Test
	fun theScalerIsFittedToTheDisplayAndCapped() {
		assertEquals(screen, upscaledSize(sd, screen))
		assertEquals(screen, upscaledSize(PixelSize(1920, 1080), screen))
		assertEquals(PixelSize(2560, 1440), upscaledSize(PixelSize(640, 360), PixelSize(3840, 2160)))
		assertNull(upscaledSize(PixelSize(1920, 1080), PixelSize(2000, 1125)))
		assertNull(upscaledSize(sd, null))
	}

	@Test
	fun aPictureThatFillsTheScreenKeepsOnlyTheFinishPass() {
		val full = ready.copy(source = screen)
		val plan = running(decideEnhance(automatic, full, 0, false))
		assertNull(plan.scaler)
		assertEquals(screen, plan.output)
		assertEquals(EnhanceChain(null, null, finish = true), EnhanceChain.of(plan, full.display))
		assertEquals(EnhanceState.Idle("nothingToDo"), decideEnhance(PictureEnhance(EnhanceUpscaler.Automatic, 0f, 0f), full, 0, false))
	}

	@Test
	fun theCeilingPicksTheRungAndNeverRunsOffTheLadder() {
		fun scaler(options: PictureEnhance, ceiling: Int) = running(decideEnhance(options, ready, ceiling, false)).scaler
		assertEquals(EnhanceScaler.Lanczos, scaler(automatic, 0))
		assertEquals(EnhanceScaler.Bicubic, scaler(automatic, 1))
		assertEquals(EnhanceScaler.Bicubic, scaler(automatic, 7))
		val lineArt = PictureEnhance(EnhanceUpscaler.LineArt)
		assertEquals(EnhanceScaler.LineArt, scaler(lineArt, 0))
		assertEquals(EnhanceScaler.Lanczos, scaler(lineArt, 1))
		assertEquals(0.75f, running(decideEnhance(lineArt, ready, 0, false)).steepen)
		assertEquals(0f, running(decideEnhance(automatic, ready, 0, false)).steepen)
	}

	@Test
	fun theChainFollowsThePlanAndIgnoresTheDisplayWithoutAScaler() {
		val plan = running(decideEnhance(automatic, ready, 0, false))
		assertEquals(EnhanceChain(ScaleKernel.Lanczos, screen, finish = true), EnhanceChain.of(plan, screen))
		assertEquals(EnhanceChain.NONE, EnhanceChain.of(null, screen))
		val scaleOnly = PictureEnhance(EnhanceUpscaler.Automatic, 0f, 0f)
		assertEquals(EnhanceChain(ScaleKernel.Lanczos, screen, finish = false), EnhanceChain.of(running(decideEnhance(scaleOnly, ready, 0, false)), screen))
	}

	@Test
	fun theBlockerNamesTheMostTellingReasonFirst() {
		assertNull(enhanceBlocker(hdr = false, softwareRenderer = false, tunnelling = false, secure = false))
		assertEquals(EnhanceUnavailable.HighDynamicRange, enhanceBlocker(true, true, true, true))
		assertEquals(EnhanceUnavailable.SoftwareRenderer, enhanceBlocker(false, true, true, true))
		assertEquals(EnhanceUnavailable.Tunnelling, enhanceBlocker(false, false, true, true))
		assertEquals(EnhanceUnavailable.SecureSurface, enhanceBlocker(false, false, false, true))
	}

	@Test
	fun heatAndBatterySaverShedPower() {
		assertFalse(enhancePowerShed(PowerManager.THERMAL_STATUS_MODERATE, batterySaver = false))
		assertTrue(enhancePowerShed(PowerManager.THERMAL_STATUS_SEVERE, batterySaver = false))
		assertTrue(enhancePowerShed(PowerManager.THERMAL_STATUS_NONE, batterySaver = true))
	}

	@Test
	fun theSourceIsMadeSquareTheWayMedia3FeedsIt() {
		assertEquals(PixelSize(1920, 1080), squareSource(960, 1080, 2f))
		assertEquals(PixelSize(1920, 1080), squareSource(1920, 540, 0.5f))
		assertEquals(PixelSize(720, 576), squareSource(720, 576, 1f))
		assertNull(squareSource(0, 1080, 1f))
	}

	@Test
	fun debandReachesFurtherOnAStretchedPicture() {
		assertEquals(1f, debandThreshold(0f) * 255f, 1e-4f)
		assertEquals(6f, debandThreshold(1f) * 255f, 1e-4f)
		assertEquals(4f, debandRadius(0f, 0.5f), 1e-4f)
		assertEquals(32f, debandRadius(1f, 2f), 1e-4f)
	}

	@Test
	fun theStatsLineAndTheLogSayTheSameThing() {
		val plan = running(decideEnhance(automatic, ready, 0, false))
		val state = EnhanceState.Running(plan)
		assertEquals("Running · GPU Lanczos scaler · 1280×720 → 2560×1440 · sharpen 0.3 · deband 0.3", state.summary())
		assertEquals("1280x720", plan.source.toString())
		assertEquals("Paused · it cannot keep up", EnhanceState.Paused(EnhancePause.DroppedFrames).summary())
		assertEquals("Unavailable · HDR and Dolby Vision play without enhancement.", EnhanceState.Unavailable(EnhanceUnavailable.HighDynamicRange).summary())
		assertEquals("droppedFrames", EnhanceState.Paused(EnhancePause.DroppedFrames).cause)
		assertEquals("highDynamicRange", EnhanceState.Unavailable(EnhanceUnavailable.HighDynamicRange).cause)
		assertEquals("running", state.name)
		assertNull(state.cause)
	}

	// The governor sees a batch of frames a second. A 30-frame window with 8 or more dropped is an overload.
	private fun EnhanceGovernor.batch(dropped: Int, rank: Int? = 0, now: Long = 0) =
		record(rendered = 30 - dropped, dropped = dropped, rank = rank, lowestRank = 1, now = now)

	private fun EnhanceGovernor.warmedUp() = apply { batch(dropped = 0) }

	@Test
	fun theGovernorDiscardsWarmUpAndJudgesOnlyFullWindows() {
		val governor = EnhanceGovernor()
		assertEquals(EnhanceGovernor.Verdict.None, governor.batch(dropped = 30))
		assertEquals(EnhanceGovernor.Verdict.None, governor.batch(dropped = 7))
		assertEquals(EnhanceGovernor.Verdict.None, governor.record(rendered = 5, dropped = 20, rank = 0, lowestRank = 1, now = 0))
		assertEquals(EnhanceGovernor.Verdict.Demoted, governor.record(rendered = 5, dropped = 5, rank = 0, lowestRank = 1, now = 0))
	}

	@Test
	fun theGovernorStepsDownTheLadderThenPausesWithGrowingCooldownsThenRetires() {
		val governor = EnhanceGovernor().warmedUp()
		assertEquals(EnhanceGovernor.Verdict.Demoted, governor.batch(dropped = 10))
		assertEquals(1, governor.ceiling)
		assertFalse(governor.isPaused)

		var now = 1_000L
		for (cooldown in longArrayOf(10_000, 20_000, 40_000)) {
			governor.warmedUp()
			assertEquals(EnhanceGovernor.Verdict.Paused, governor.batch(dropped = 10, rank = 1, now = now))
			assertTrue(governor.isPaused)
			assertFalse(governor.resume(now + cooldown - 1))
			assertTrue(governor.resume(now + cooldown))
			assertFalse(governor.resume(now + cooldown))
			now += cooldown
		}
		governor.warmedUp()
		assertEquals(EnhanceGovernor.Verdict.Retired, governor.batch(dropped = 10, rank = 1, now = now))
		assertTrue(governor.isPaused)
		assertFalse(governor.resume(Long.MAX_VALUE))
		governor.reset()
		assertFalse(governor.isPaused)
		assertEquals(0, governor.ceiling)
	}

	@Test
	fun aSeekOrAFinishOnlyChainGoesStraightToPausing() {
		val governor = EnhanceGovernor().warmedUp()
		governor.restartCount()
		assertEquals(EnhanceGovernor.Verdict.None, governor.batch(dropped = 30))
		assertEquals(EnhanceGovernor.Verdict.Paused, governor.batch(dropped = 30, rank = null, now = 5))
	}
}

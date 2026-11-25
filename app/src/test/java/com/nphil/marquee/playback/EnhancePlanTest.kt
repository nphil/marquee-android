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
		assertEquals(2f, debandThreshold(0f) * 255f, 1e-4f)
		assertEquals(12f, debandThreshold(1f) * 255f, 1e-4f)
		assertEquals(4f, debandRadius(0f, 0.5f), 1e-4f)
		assertEquals(32f, debandRadius(1f, 2f), 1e-4f)
	}

	@Test
	fun sharpenHalfIsTheOldFullStrengthAndFullIsTwiceThat() {
		assertEquals(0f, sharpenWeight(0f))
		// The old weight was 0.02 + 0.18 * strength, so 1.0 gave 0.2; 2.0 would have given 0.38.
		assertEquals(0.2f, sharpenWeight(0.5f), 1e-6f)
		assertEquals(0.38f, sharpenWeight(1f), 1e-6f)
	}

	@Test
	fun theNewEffectsAreOffAtZeroAndGrowWithTheirStrength() {
		assertEquals(0f, noiseSigma(0f))
		assertEquals(0f, deblockThreshold(0f))
		assertTrue(noiseSigma(1f) > noiseSigma(0.1f) && noiseSigma(0.1f) > 0f)
		assertEquals(3f, deblockThreshold(0f + 1e-6f) * 255f, 1e-3f)
		assertEquals(20f, deblockThreshold(1f) * 255f, 1e-4f)
		assertEquals(1f, noiseBlend(1f))
	}

	@Test
	fun theNewStrengthsAreOffByDefaultAndEachKeepsTheFinishPassGoing() {
		val defaults = PictureEnhance()
		assertEquals(0f, defaults.noiseReduction)
		assertEquals(0f, defaults.deblock)
		assertEquals(0f, defaults.vivid)
		val full = ready.copy(source = screen)
		val bare = PictureEnhance(EnhanceUpscaler.Automatic, sharpen = 0f, deband = 0f)
		assertEquals(EnhanceState.Idle("nothingToDo"), decideEnhance(bare, full, 0, false))
		for (one in listOf(bare.copy(noiseReduction = 0.4f), bare.copy(deblock = 0.4f), bare.copy(vivid = 0.4f))) {
			val plan = running(decideEnhance(one, full, 0, false))
			assertTrue(plan.finishes)
			assertEquals(one.noiseReduction, plan.noiseReduction)
			assertEquals(one.deblock, plan.deblock)
			assertEquals(one.vivid, plan.vivid)
			assertEquals(EnhanceChain(null, null, finish = true), EnhanceChain.of(plan, full.display))
		}
	}

	@Test
	fun automaticTakesItsStrongestScalerOnlyForLowResolutionSources() {
		assertEquals(listOf(EnhanceScaler.Lanczos, EnhanceScaler.Bicubic), EnhanceScaler.ladder(EnhanceUpscaler.Automatic, 720))
		assertEquals(listOf(EnhanceScaler.Lanczos, EnhanceScaler.Bicubic), EnhanceScaler.ladder(EnhanceUpscaler.Automatic, 480))
		assertEquals(listOf(EnhanceScaler.Bicubic), EnhanceScaler.ladder(EnhanceUpscaler.Automatic, 721))
		assertEquals(listOf(EnhanceScaler.LineArt, EnhanceScaler.Lanczos), EnhanceScaler.ladder(EnhanceUpscaler.LineArt, 1080))
		val hd = ready.copy(source = PixelSize(1920, 1080), display = PixelSize(3840, 2160))
		assertEquals(EnhanceScaler.Bicubic, running(decideEnhance(automatic, hd, 0, false)).scaler)
	}

	@Test
	fun thePageNotesAPictureThatIsAlreadyAboutTheViewsSize() {
		assertTrue(nearScreenResolution(PixelSize(1920, 1080), PixelSize(2000, 1080)))
		assertTrue(nearScreenResolution(PixelSize(3840, 2160), PixelSize(2560, 1440)))
		assertFalse(nearScreenResolution(PixelSize(1280, 720), PixelSize(2560, 1440)))
		assertFalse(nearScreenResolution(null, PixelSize(2560, 1440)))
		assertFalse(nearScreenResolution(PixelSize(1920, 1080), null))
	}

	@Test
	fun theStatsLineNamesTheNewEffectsOnlyWhileTheyAreOn() {
		val plan = running(decideEnhance(automatic.copy(noiseReduction = 0.5f, vivid = 0.25f), ready, 0, false))
		assertEquals(
			"Running · GPU Lanczos scaler · 1280×720 → 2560×1440 · sharpen 0.3 · deband 0.3 · noise reduction 0.5 · vivid 0.25",
			EnhanceState.Running(plan).summary(),
		)
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

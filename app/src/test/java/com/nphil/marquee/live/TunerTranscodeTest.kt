package com.nphil.marquee.live

import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.live.TunerTranscode.Profile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerTranscodeTest {
	private val everything = { _: String, _: Int? -> true }
	private val limited = Profile("mobile", "h264", 720, progressive = true)
	private val full = Profile("full", "hevc", null, progressive = true)

	@Test
	fun theBestProfileKeepsTheResolutionIsProgressiveAndDecodes() {
		val interlaced = Profile("i", "h264", null, progressive = false)
		val offer = listOf(interlaced, limited, full)
		assertEquals("mobile", TunerTranscode.choose(offer, 480, everything).first?.name)
		// 1080 lines: only the unlimited profile keeps the picture; the interlaced one is skipped.
		assertEquals("full", TunerTranscode.choose(offer, 1080, everything).first?.name)
		// An unknown source qualifies only an unlimited profile.
		assertEquals("full", TunerTranscode.choose(offer, null, everything).first?.name)
		assertNull(TunerTranscode.choose(listOf(limited), 1080, everything).first)
		// No decoder for HEVC here: nothing qualifies, so the original is requested.
		assertNull(TunerTranscode.choose(listOf(full), 1080, { codec, _ -> codec == "h264" }).first)
		assertEquals("no-profile", TunerTranscode.choose(listOf(full), 1080, { _, _ -> false }).second)
	}

	@Test
	fun aTvThatPublishesNoProfilesGetsTheBuiltInRuleAndUnknownFieldsAreIgnored() {
		assertEquals("mobile", TunerTranscode.choose(null, 720, everything).first?.name)
		assertEquals("hd", TunerTranscode.choose(null, 1080, everything).second)
		assertEquals("unknown", TunerTranscode.choose(null, null, everything).second)
		assertNull(TunerTranscode.offer(CoreJson.parseToJsonElement("""{"holder":null}""")))
		val offer = TunerTranscode.offer(CoreJson.parseToJsonElement(
			"""{"transcode":{"enabled":true,"future":1,"profiles":[{"name":"full","codec":"HEVC","maxHeight":null,"progressive":true,"kbps":null,"x":[]},{"codec":"h264"}]}}""",
		))!!
		assertEquals(listOf("full"), offer.map { it.name })
		assertEquals("hevc", offer[0].codec)
		assertNull(offer[0].maxHeight)
		assertEquals(emptyList<Profile>(), TunerTranscode.offer(CoreJson.parseToJsonElement("""{"transcode":{"enabled":false,"profiles":[]}}""")))
	}

	@Test
	fun onlyALumaTunerIsAskedHeightsAreLearnedFromUnlimitedTunesAndADeclineLastsTheRun() {
		val memory = TunerTranscode()
		assertEquals("not-luma", memory.decide(false, "tv", "2.1", null, everything).reason)
		assertEquals("unknown", memory.decide(true, "tv", "2.1", null, everything).reason)
		memory.noted(480, "tv", "2.1")
		val sd = memory.decide(true, "tv", "2.1", null, everything)
		assertTrue(sd.asked && sd.source == 480)
		memory.noted(720, "tv", "2.1") // a limited tune's pictures say nothing about the source
		assertEquals(480, memory.decide(true, "tv", "2.1", null, everything).source)
		memory.decide(true, "tv", "7.1", null, everything)
		memory.noted(1080, "tv", "7.1")
		assertEquals("hd", memory.decide(true, "tv", "7.1", null, everything).reason)
		memory.decline("tv")
		assertEquals("declined", memory.decide(true, "tv", "2.1", null, everything).reason)
		assertTrue(memory.asks("other"))
		assertEquals(
			"http://tv:5004/auto/v2.1?device=Pixel&transcode=mobile",
			TunerTranscode.ask("http://tv:5004/auto/v2.1?device=Pixel".toHttpUrl(), limited).toString(),
		)
	}

	@Test
	fun smoothMotionSkipsProfilesThatHalveAnInterlacedStationUnlessItIsKnownProgressive() {
		val heavy = Profile("heavy", "h264", null, progressive = true, interlacedFps = 30)
		val keeps = Profile("keeps", "h264", null, progressive = true)
		fun pick(offer: List<Profile>?, interlaced: Boolean?, smooth: Boolean = true, source: Int? = 1080) =
			TunerTranscode.choose(offer, source, everything, interlaced, smooth).let { it.first?.name to it.second }
		assertEquals(null to "motion", pick(listOf(heavy), true))
		assertEquals(null to "motion", pick(listOf(heavy), null))
		assertEquals("heavy" to "profile", pick(listOf(heavy), false))
		assertEquals("heavy" to "profile", pick(listOf(heavy), true, smooth = false))
		// A profile with no interlacedFps keeps the field rate, so it is taken after the skipped one.
		assertEquals("keeps" to "profile", pick(listOf(heavy, keeps), true))
		assertEquals("keeps" to "profile", pick(listOf(keeps), null))
		assertEquals(null to "motion", pick(null, true, source = 480))
		assertEquals("mobile" to "sd", pick(null, false, source = 480))
		assertEquals("mobile" to "sd", pick(null, null, smooth = false, source = 480))
		val offer = TunerTranscode.offer(CoreJson.parseToJsonElement(
			"""{"transcode":{"profiles":[{"name":"heavy","codec":"h264","progressive":true,"interlacedFps":30},{"name":"full","codec":"h264","progressive":true,"interlacedFps":null}]}}""",
		))!!
		assertEquals(listOf(30, null), offer.map { it.interlacedFps })
	}

	@Test
	fun interlaceComesFromTheLineupOrFromTheLastOriginalStreamTune() {
		val memory = TunerTranscode()
		val heavy = Profile("heavy", "h264", null, progressive = true, interlacedFps = 30)
		fun decide(lineup: Boolean? = null) = memory.decide(true, "tv", "7.1", listOf(heavy), everything, smooth = true, lineupInterlaced = lineup)
		val first = decide()
		assertTrue(!first.asked && first.reason == "motion" && first.interlaced == null)
		memory.notedScan(false, "tv", "7.1")
		val progressive = decide()
		assertEquals("heavy", progressive.profile?.name)
		assertEquals(false, progressive.interlaced)
		// The profile's own output teaches nothing; the lineup's word wins over what was learned.
		memory.notedScan(true, "tv", "7.1")
		assertEquals(false, decide().interlaced)
		assertEquals("motion", decide(lineup = true).reason)
		// That tune asked for the original stream, so what was learned before is dropped to be learned again.
		assertNull(decide().interlaced)
	}

	@Test
	fun theOriginalStreamLooksInterlacedFromItsHeightAndFrameRate() {
		assertEquals(false, TunerTranscode.looksInterlaced(720, 59.94f))
		assertEquals(true, TunerTranscode.looksInterlaced(1080, 29.97f))
		assertEquals(true, TunerTranscode.looksInterlaced(480, 29.97f))
		assertEquals(false, TunerTranscode.looksInterlaced(1080, 59.94f))
		assertNull(TunerTranscode.looksInterlaced(1080, -1f))
	}

	@Test
	fun aRefusedOrPictureLessAskIsRetriedOnceButASharingRefusalIsNot() {
		assertTrue(retriesWithoutTranscode(asked = true, status = 400, noVideo = false, sharingRefusal = false))
		assertTrue(retriesWithoutTranscode(asked = true, status = 503, noVideo = false, sharingRefusal = false))
		assertTrue(retriesWithoutTranscode(asked = true, status = null, noVideo = true, sharingRefusal = false))
		assertFalse(retriesWithoutTranscode(asked = true, status = 503, noVideo = false, sharingRefusal = true))
		assertFalse(retriesWithoutTranscode(asked = true, status = 403, noVideo = false, sharingRefusal = false))
		assertFalse(retriesWithoutTranscode(asked = false, status = 503, noVideo = true, sharingRefusal = false))
	}

	@Test
	fun aStreamThatEndsIsReopenedTwiceAMinuteAndTheSecondReopenDropsTheConversionWhenItEndedQuickly() {
		val policy = TunerReopen()
		assertTrue(policy.ended(100_000).let { it.reopen && it.n == 1 && !it.withoutTranscode })
		assertTrue(policy.ended(103_000).let { it.reopen && it.n == 2 && it.withoutTranscode })
		assertFalse(policy.ended(104_000).reopen)
		assertTrue(policy.ended(170_000).reopen)
		val slow = TunerReopen()
		slow.ended(0)
		assertFalse(slow.ended(20_000).withoutTranscode)
	}
}

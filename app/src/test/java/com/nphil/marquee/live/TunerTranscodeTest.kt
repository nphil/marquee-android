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
		assertNull(TunerTranscode.choose(listOf(full), 1080) { codec, _ -> codec == "h264" }.first)
		assertEquals("no-profile", TunerTranscode.choose(listOf(full), 1080) { _, _ -> false }.second)
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

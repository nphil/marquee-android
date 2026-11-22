package com.nphil.marquee.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's calls as the bridge words them (bridge/Tests/MarqueeBridgeTests/PlayerCallsTests.swift). */
class PlayCoreTest {
	@Test
	fun aPlanCarriesTheRouteAndTheStreamTheBridgeChose() {
		val json = """
			{"session":"s1","item":{"ratingKey":"1","type":"movie","title":"Heat","duration":7200000,"viewOffset":600000},
			 "mediaIndex":0,"startAt":600,"duration":7200,
			 "route":{"kind":"directPlay","reasons":[],"audio":2,"audioIsStandIn":false,"reencodesVideo":false,
			          "subtitle":{"kind":"notShown","track":5}},
			 "stream":{"kind":"file","url":"http://192.0.2.10:32400/library/parts/9/1/file.mkv","headers":{"X-Plex-Token":"t"},"offset":0},
			 "intro":[{"start":30000,"end":90000,"isFinal":false}],"credits":[],
			 "chapters":[{"title":"Opening","start":0,"end":60000}],
			 "isAnime":false,"quality":0,"adapts":false,"badge":{"resolution":"4K","range":"dolbyVision","sound":"atmos"},
			 "thumbnails":{"url":"http://192.0.2.10:32400/library/parts/9/indexes/sd","headers":{"X-Plex-Token":"t"}}}
		""".trimIndent()

		val plan = CoreJson.decodeFromString(PlayPlan.serializer(), json)

		assertEquals(RouteKind.DirectPlay, plan.route.kind)
		assertEquals(2, plan.route.audio)
		assertEquals("notShown", plan.route.subtitle.kind)
		assertEquals(PlayStream.StreamKind.File, plan.stream?.kind)
		assertEquals("t", plan.stream?.headers?.get("X-Plex-Token"))
		assertEquals(600.0, plan.startAt, 0.0)
		assertEquals(30_000, plan.intro.single().start)
		assertNull(plan.probe)
		assertEquals("4K, Dolby Vision, Dolby Atmos", plan.badge.spoken)
		assertEquals("t", plan.thumbnails?.headers?.get("X-Plex-Token"))
	}

	@Test
	fun aPlanThatAsksForAReadHasNoStreamYet() {
		val json = """
			{"session":"s1","item":{"ratingKey":"1","type":"movie"},"route":{"kind":"serverTranscode"},
			 "probe":{"url":"http://192.0.2.10:32400/library/parts/9/1/file.mkv","headers":{},"maxBytes":4194304,
			          "windowSeconds":0.5,"timeoutSeconds":3}}
		""".trimIndent()

		val plan = CoreJson.decodeFromString(PlayPlan.serializer(), json)

		assertNull(plan.stream)
		assertEquals(4_194_304, plan.probe?.maxBytes)
		assertNull(plan.thumbnails)
		assertEquals(RouteKind.ServerTranscode, plan.route.kind)
	}

	@Test
	fun aStartRequestSpeaksTheBridgesKeys() {
		val request = StartRequest(
			ratingKey = "1",
			quality = QualityPreset.AUTO,
			savesData = false,
			metered = true,
			directPlay = DirectPlayMode.Auto,
			capabilities = Capabilities(
				hevc = true, hevcMain10 = true, av1 = false, hdrDisplay = true, dolbyVisionProfiles = listOf(5),
				nativeAudioCodecs = listOf("dca", "truehd"), directContainers = listOf("mkv"),
			),
			network = 1,
			now = 100.0,
			probe = Reading(4_000_000, 0.4),
		)

		val json = CoreJson.encodeToJsonElement(StartRequest.serializer(), request).jsonObject

		assertEquals("-1", json.getValue("quality").jsonPrimitive.content)
		assertEquals("auto", json.getValue("directPlay").jsonPrimitive.content)
		assertEquals("4000000", json.getValue("probe").jsonObject.getValue("bytes").jsonPrimitive.content)
		assertEquals("mkv", json.getValue("capabilities").jsonObject.getValue("directContainers").toString().trim('[', ']', '"'))
		assertFalse("a start that has not been tried on the server says nothing of it", "onDeviceFailed" in json)
		assertFalse("session" in json)
	}

	@Test
	fun theQualityPresetsAreCoresRungs() {
		assertEquals("Auto", QualityPreset.title(QualityPreset.AUTO))
		assertEquals("Original", QualityPreset.title(QualityPreset.ORIGINAL))
		assertEquals("8 Mbps 1080p", QualityPreset.title(8_000))
		assertEquals("2 Mbps 720p", QualityPreset.title(2_000))
		assertTrue(QualityPreset.all.first() == QualityPreset.AUTO)
	}

	@Test
	fun theAutoplayAndTapAnswersAreTheBridgesWords() {
		val countdown = CoreJson.decodeFromString(AutoplayPlan.serializer(), """{"atCredits":"countdown","seconds":5,"playsNextAtEnd":true}""")
		assertEquals(AutoplayPlan(AtCredits.Countdown, 5, true), countdown)
		val now = CoreJson.decodeFromString(AutoplayPlan.serializer(), """{"atCredits":"playNow","playsNextAtEnd":false}""")
		assertEquals(AtCredits.PlayNow, now.atCredits)

		val skip = CoreJson.decodeFromString(TapAnswer.serializer(), """{"action":"skip","side":"back"}""")
		assertEquals(TapAnswer(TapAction.Skip, SkipSide.Back), skip)
		val single = CoreJson.decodeFromString(TapAnswer.serializer(), """{"action":"single","window":0.3}""")
		assertEquals(0.3, single.window!!, 0.0)
		val retry = CoreJson.decodeFromString(SkipAnswer.serializer(), """{"verdict":"retry","retried":true}""")
		assertEquals(SkipVerdict.Retry, retry.verdict)
	}

	@Test
	fun theServersAccountOfItsConversionDecodes() {
		val json = """{"active":true,"video":"copy","audio":"transcode","subtitle":"none","speed":2.4,"throttled":false,"fullPipeline":false}"""

		val state = CoreJson.decodeFromString(TranscodeState.serializer(), json)

		assertTrue(state.active)
		assertEquals("copy", state.video)
		assertEquals(2.4, state.speed!!, 0.0)
		assertNull(state.decoding)
		assertFalse(CoreJson.decodeFromString(TranscodeState.serializer(), """{"active":false}""").active)
	}
}

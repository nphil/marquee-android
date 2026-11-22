package com.nphil.marquee.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastCoreTest {
	@Test
	fun aReplacementCarriesThePositionTrackAndRecordedVersionToSwift() {
		val request = CastStartRequest(
			ratingKey = "2", session = "player", startAt = 672.5, fromStart = null, mediaIndex = 1,
			quality = QualityPreset.AUTO, directPlay = DirectPlayMode.Auto, now = 123.0, audio = 2,
			subtitle = SubtitlePick.track(5), receiverModel = "Chromecast", replacing = 3, receiverFailed = true,
			recordingMediaID = 11,
		)

		val wire = CoreJson.encodeToJsonElement(CastStartRequest.serializer(), request).jsonObject
		assertEquals("672.5", wire.getValue("startAt").jsonPrimitive.content)
		assertEquals("3", wire.getValue("replacing").jsonPrimitive.content)
		assertEquals("true", wire.getValue("receiverFailed").jsonPrimitive.content)
		assertEquals("5", wire.getValue("subtitle").jsonObject.getValue("id").jsonPrimitive.content)
		assertEquals("11", wire.getValue("recordingMediaID").jsonPrimitive.content)
		assertFalse("fromStart" in wire)
	}

	@Test
	fun aConversionSuppliesAReceiverUrlAndKeepsTheThumbnailCredentialSeparate() {
		val wire = """
			{"plan":{"session":"player","item":{"ratingKey":"2"},"startAt":672.5,
			 "route":{"kind":"serverTranscode","subtitle":{"kind":"burnedIn","track":5}},
			 "stream":{"kind":"hls","url":"http://192.0.2.10/live.m3u8?X-Plex-Token=temporary","headers":{},"session":"conversion","offset":0},
			 "thumbnails":{"url":"http://192.0.2.10/index","headers":{"X-Plex-Token":"device"}}},
			 "contentType":"application/x-mpegURL"}
		""".trimIndent()

		val cast = CoreJson.decodeFromString(CastPlan.serializer(), wire)
		val stream = requireNotNull(cast.plan.stream)
		assertEquals("application/x-mpegURL", cast.contentType)
		assertEquals(PlayStream.StreamKind.Hls, stream.kind)
		assertEquals(0.0, stream.offset, 0.0)
		assertEquals(672.5, cast.plan.startAt, 0.0)
		assertEquals(DELIVERY_BURNED_IN, cast.plan.route.subtitle.kind)
		assertTrue(stream.url.endsWith("X-Plex-Token=temporary"))
		assertTrue(stream.headers.isEmpty())
		assertEquals("device", cast.plan.thumbnails?.headers?.get("X-Plex-Token"))
	}

	@Test
	fun aReceiverStopKeepsTheLastPosition() {
		val progress = CoreJson.decodeFromString(CastProgress.serializer(), """{"state":"stopped","position":672.5,"end":"stopped"}""")

		assertEquals(672.5, progress.position, 0.0)
		assertEquals(CastEnd.Stopped, progress.end)
	}
}

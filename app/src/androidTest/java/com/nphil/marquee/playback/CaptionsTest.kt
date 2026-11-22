package com.nphil.marquee.playback

import androidx.media3.common.text.Cue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Media3's SubRip reading, on a device because the parser builds Android text: what the player's own captions rely on. */
@RunWith(AndroidJUnit4::class)
class CaptionsTest {
	private val captions = Captions.parse(
		"""
		1
		00:00:01,000 --> 00:00:03,000
		Hello

		2
		00:00:02,500 --> 00:00:04,000
		{\an8}Top line
		""".trimIndent().toByteArray(),
	)

	@Test
	fun linesShowOnlyWhileTheirTimeIsOn() {
		assertTrue(captions.framesAt(500).isEmpty())
		assertEquals(listOf("Hello"), captions.framesAt(1_000).map { it.cue.text.toString() })
		assertEquals(listOf("Hello", "Top line"), captions.framesAt(2_700).map { it.cue.text.toString() })
		assertTrue(captions.framesAt(4_000).isEmpty())
	}

	@Test
	fun anEightTagPutsTheLineAtTheTopAndIsNotShown() {
		val top = captions.framesAt(3_000).single().cue
		assertEquals("Top line", top.text.toString())
		assertEquals(Cue.LINE_TYPE_FRACTION, top.lineType)
		assertEquals(Cue.ANCHOR_TYPE_START, top.lineAnchor)
		assertTrue("the line sits near the top: ${top.line}", top.line < 0.2f)
	}

	@Test
	fun aLineWithNoTagHasNoPlaceOfItsOwn() {
		val frame = captions.framesAt(1_000).single()
		assertEquals(Cue.DIMEN_UNSET, frame.cue.line, 0f)
		assertEquals(1_000L, frame.startMs)
		assertEquals(2_000L, frame.durationMs)
	}
}

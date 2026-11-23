package com.nphil.marquee.playback

import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLoadControlTest {
	// Media3's sample queue needs Android's SparseArray, so a JVM test cannot show the keyframe surviving the trim;
	// this pins the setting that keeps it, and that nothing more is kept.
	@Test
	fun theTitlePlayerKeepsTheKeyframeBehindThePlayheadAndNothingMore() {
		val load = titleLoadControl()
		assertTrue(load.retainBackBufferFromKeyframe(PlayerId.UNSET))
		assertEquals(0L, load.getBackBufferDurationUs(PlayerId.UNSET))
	}
}

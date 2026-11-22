package com.nphil.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BifTest {
	private val words = listOf("aa", "bbb", "cccc")
	private val bif = Bif.parse(bifOf(listOf(0L, 10L, 20L), words))!!

	@Test
	fun readsEachFramesTimeAndBytes() {
		assertEquals(3, bif.count)
		assertEquals(listOf(0.0, 10.0, 20.0), (0 until bif.count).map { bif.timeOf(it) })
		// The last frame ends where the closing entry says.
		assertEquals(words, (0 until bif.count).map { String(bif.data, bif.start(it), bif.length(it)) })
	}

	@Test
	fun showsTheLastFrameAtOrBeforeATime() {
		assertEquals(0, bif.indexAt(-3.0))
		assertEquals(0, bif.indexAt(9.99))
		assertEquals(1, bif.indexAt(10.0))
		assertEquals(1, bif.indexAt(19.0))
		assertEquals(2, bif.indexAt(3_600.0))
	}

	@Test
	fun countsTicksInTheHeadersUnitWhichDefaultsToASecond() {
		assertEquals(6.0, Bif.parse(bifOf(listOf(0L, 3L), listOf("a", "b"), multiplier = 2_000L))!!.timeOf(1), 0.0)
		assertEquals(7.0, Bif.parse(bifOf(listOf(0L, 7L), listOf("a", "b"), multiplier = 0L))!!.timeOf(1), 0.0)
	}

	@Test
	fun refusesWhatIsNotAnIndexOfFrames() {
		val whole = bifOf(listOf(0L), listOf("a"))
		val wrongMagic = whole.copyOf().also { it[1] = 0 }
		assertNull(Bif.parse(ByteArray(0)))
		assertNull(Bif.parse(whole.copyOf(64)))
		assertNull(Bif.parse(wrongMagic))
		assertNull(Bif.parse(bifOf(emptyList(), emptyList())))
	}

	@Test
	fun keepsOnlyTheFramesThatLieInsideTheFile() {
		val whole = bifOf(listOf(0L, 10L, 20L), words)
		assertEquals(2, Bif.parse(whole.copyOf(whole.size - 1))!!.count)
	}
}

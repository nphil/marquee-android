package com.nphil.marquee.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiskFramesTest {
	@get:Rule
	val folder = TemporaryFolder()

	// A file's age is the clock's when it was written or read, so a test says which is older.
	private var clock = 0L
	private val jpeg = ByteArray(4) { 1 }

	private fun disk(cap: Long) = DiskFrames(folder.root, cap) { clock }

	@Test
	fun keepsAFrameAndListsThePartsSlots() {
		val disk = disk(cap = 100)
		assertTrue(disk.write("7-9", 3, jpeg))
		assertTrue(disk.write("7-9", 12, jpeg))
		// A write that never finished is no frame.
		File(folder.root, "7-9/5.tmp").writeBytes(jpeg)

		assertEquals(listOf(3, 12), disk.slots("7-9").sorted())
		assertArrayEquals(jpeg, disk.read("7-9", 3))
		assertNull(disk.read("7-9", 4))
		assertEquals(0, disk.slots("8-1").size)
	}

	@Test
	fun dropsTheOldestFilesOncePastTheCapAcrossParts() {
		val disk = disk(cap = 10)
		clock = 10_000
		disk.write("a", 1, jpeg)
		clock = 20_000
		disk.write("b", 1, jpeg)
		clock = 30_000
		disk.write("b", 2, jpeg)

		assertNull(disk.read("a", 1))
		assertNotNull(disk.read("b", 1))
		assertNotNull(disk.read("b", 2))
		// A part with no frame left leaves no folder.
		assertFalse(File(folder.root, "a").exists())
	}

	@Test
	fun aReadCountsAsAUse() {
		val disk = disk(cap = 10)
		clock = 10_000
		disk.write("a", 1, jpeg)
		clock = 20_000
		disk.write("a", 2, jpeg)
		clock = 30_000
		disk.read("a", 1)
		clock = 40_000
		disk.write("a", 3, jpeg)

		assertNull(disk.read("a", 2))
		assertNotNull(disk.read("a", 1))
		assertNotNull(disk.read("a", 3))
	}
}

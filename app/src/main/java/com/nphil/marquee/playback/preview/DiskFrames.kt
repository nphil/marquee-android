package com.nphil.marquee.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Frames kept as JPEG files, `<root>/<part>/<slot>.jpg`, so a title opened again shows its previews at once. [cap] is
 * the most they may take across every part. When a write passes it the files used longest ago go first: a read counts as
 * a use. The device's cache folder is the app's to fill, but the system clears it when space runs short, so nothing here
 * is the only copy of anything.
 */
internal class DiskFrames(
	private val root: File,
	private val cap: Long,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private class Entry(val file: File, val size: Long, val used: Long)

	// What the folders held when last walked, and what has been written since: they are walked again only once that
	// passes the cap, so most writes cost the one file and not a look at every other.
	private var held = UNKNOWN

	/** The slots [part] has a frame for. */
	fun slots(part: String): IntArray {
		val names = File(root, part).list() ?: return IntArray(0)
		return names.mapNotNull { name -> if (name.endsWith(SUFFIX)) name.removeSuffix(SUFFIX).toIntOrNull() else null }.toIntArray()
	}

	/** The JPEG kept for [slot], or null when there is none. */
	fun read(part: String, slot: Int): ByteArray? {
		val file = file(part, slot)
		return try {
			file.readBytes().also { file.setLastModified(now()) }
		} catch (_: IOException) {
			null
		}
	}

	/** Keeps [bytes] as [slot] of [part]; false when the disk would not take them. */
	fun write(part: String, slot: Int, bytes: ByteArray): Boolean = synchronized(lock) {
		val target = file(part, slot)
		// Written beside the target and moved in, so a reader never meets half a file.
		val temporary = File(target.parentFile, "$slot$TEMPORARY")
		val kept = try {
			target.parentFile?.mkdirs()
			temporary.writeBytes(bytes)
			temporary.renameTo(target)
		} catch (_: IOException) {
			false
		}
		if (kept) {
			target.setLastModified(now())
			if (held == UNKNOWN || held + bytes.size > cap) {
				trim()
			} else {
				held += bytes.size
			}
		} else {
			temporary.delete()
		}
		kept
	}

	private fun file(part: String, slot: Int) = File(File(root, part), "$slot$SUFFIX")

	private fun trim() {
		val entries = root.listFiles { file -> file.isDirectory }.orEmpty().flatMap { directory ->
			directory.listFiles().orEmpty().map { Entry(it, it.length(), it.lastModified()) }
		}
		var total = entries.sumOf { it.size }
		if (total > cap) {
			// A file's time is read once: another thread touching one mid-sort would break the comparison.
			for (entry in entries.sortedWith(compareBy({ it.used }, { it.file.path }))) {
				if (total <= cap) break
				if (entry.file.delete()) total -= entry.size
			}
			root.listFiles { file -> file.isDirectory && file.list().isNullOrEmpty() }.orEmpty().forEach { it.delete() }
		}
		held = total
	}

	private companion object {
		const val SUFFIX = ".jpg"
		const val TEMPORARY = ".tmp"
		const val UNKNOWN = -1L

		/** One for every folder under every root: a title's engine can be closing while the next one opens. */
		val lock = Any()
	}
}

/** One part's frames on disk, as the pictures the engines hold. */
internal class PictureStore<T : Any>(
	private val disk: DiskFrames,
	private val part: String,
	private val pictures: Pictures<T>,
	private val io: CoroutineDispatcher,
) {
	suspend fun slots(): IntArray = withContext(io) { disk.slots(part) }

	suspend fun load(slot: Int): T? = withContext(io) {
		disk.read(part, slot)?.let { pictures.decode(it, 0, it.size) }
	}

	/** How many bytes were kept for [slot]; 0 when none could be. */
	suspend fun save(slot: Int, picture: T): Int = withContext(io) {
		val bytes = pictures.encode(picture)
		if (bytes != null && disk.write(part, slot, bytes)) bytes.size else 0
	}
}

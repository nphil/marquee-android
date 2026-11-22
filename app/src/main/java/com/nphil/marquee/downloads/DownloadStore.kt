package com.nphil.marquee.downloads

import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

@Serializable
internal data class DownloadScope(val server: String, val profile: String, val verified: Boolean = false)

@Serializable
internal enum class DownloadPhase {
	Queued, Converting, Downloading, Paused, Failed, Ready, Removed;

	val active: Boolean get() = this == Queued || this == Converting || this == Downloading
}

/** The index keeps no transfer URLs or authorization headers. The media descriptions are separate files. */
@Serializable
internal data class DownloadEntry(
	val id: String,
	val scope: DownloadScope,
	val title: DownloadTitle,
	val version: Int,
	val choice: DownloadChoice,
	val isAnime: Boolean,
	val countries: List<String>,
	val watch: DownloadWatch,
	val queuedAt: Long,
	val phase: DownloadPhase = DownloadPhase.Queued,
	val conversion: Conversion? = null,
	val submissionPending: Boolean = false,
	val conversionFraction: Double? = null,
	val restartConversion: Boolean = false,
	val downloaded: Long = 0,
	val length: Long? = null,
	val validator: DownloadValidator? = null,
	val assetsFetched: Boolean = false,
	val subtitles: List<Int> = emptyList(),
	val failure: String? = null,
) {
	val fraction: Float? get() = when (phase) {
		DownloadPhase.Ready -> 1f
		DownloadPhase.Converting -> conversionFraction?.toFloat()?.coerceIn(0f, 1f)
		DownloadPhase.Queued, DownloadPhase.Downloading, DownloadPhase.Paused ->
			length?.takeIf { it > 0 && (downloaded > 0 || phase == DownloadPhase.Downloading) }
				?.let { (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
		else -> null
	}
}

internal data class LocalDownload(
	val entry: DownloadEntry,
	val media: File,
	val snapshot: File,
	val decision: File?,
	val subtitles: Map<Int, String>,
)

internal data class DownloadDisk(val downloads: Long = 0, val available: Long = 0)

internal class StorageFailure(cause: IOException? = null) : Exception("Couldn't save the download. Check available storage.", cause)

/** One atomic record per download. Media writes never hold the lock for the index. */
internal class DownloadStore(private val root: File) {
	private val lock = Mutex()
	private val entries = LinkedHashMap<String, DownloadEntry>()
	private var loaded = false
	private val records = MutableStateFlow<List<DownloadEntry>>(emptyList())
	val state = records.asStateFlow()
	private val settings = MutableStateFlow(DownloadPreferences())
	val preferences = settings.asStateFlow()

	suspend fun load() = onDisk { Unit }

	suspend fun get(id: String): DownloadEntry? = onDisk { entries[id] }

	suspend fun create(scope: DownloadScope, options: DownloadChoices, version: Int, choice: DownloadChoice): Boolean = onDisk {
		if (entries.values.any { it.scope == scope && it.title.ratingKey == options.title.ratingKey && it.phase != DownloadPhase.Removed }) return@onDisk false
		if (choice.target != null && entries.values.any {
			it.scope == scope && it.title.ratingKey == options.title.ratingKey && it.submissionPending
		}) throw DownloadFailure("Plex may already be preparing a converted copy. Choose Original instead.")
		val entry = DownloadEntry(
			UUID.randomUUID().toString(), scope, options.title, version, choice, options.isAnime, options.countries, options.watch,
			queuedAt = System.currentTimeMillis(),
		)
		try {
			storage { write(snapshot(entry), options.snapshot) }
			put(entry)
		} catch (e: StorageFailure) {
			folder(entry).deleteRecursively()
			throw e
		}
		true
	}

	suspend fun update(id: String, change: (DownloadEntry) -> DownloadEntry): DownloadEntry? = onDisk {
		val old = entries[id] ?: return@onDisk null
		val new = change(old)
		if (old != new) put(new)
		new
	}

	suspend fun setPreferences(change: (DownloadPreferences) -> DownloadPreferences): DownloadPreferences = onDisk {
		val current = settings.value
		val next = change(current)
		if (next != current) {
			storage { write(root.resolve("settings.json"), CoreJson.encodeToString(DownloadPreferences.serializer(), next)) }
			settings.value = next
		}
		current
	}

	suspend fun playback(entry: DownloadEntry): LocalDownload? = onDisk {
		val file = media(entry)
		val description = snapshot(entry)
		val converted = decision(entry).takeIf { entry.choice.target != null }
		if (!file.isFile || file.length() != entry.length || !description.isFile || converted?.isFile == false) return@onDisk null
		val subs = entry.subtitles.mapNotNull { id -> subtitle(entry, id).takeIf(File::isFile)?.let { id to it.path } }.toMap()
		LocalDownload(entry, file, description, converted, subs)
	}

	/** Removes video and artwork but retains the watch and conversion receipts until Plex has heard them. */
	suspend fun discardMedia(entry: DownloadEntry) = onDisk {
		val folder = folder(entry)
		for (file in folder.listFiles().orEmpty()) {
			if (!file.name.startsWith("entry.json") && !file.delete()) throw StorageFailure()
		}
	}

	suspend fun forget(entry: DownloadEntry) = onDisk {
		// No ID came back, so removing the local file must not permit another server submission.
		if (entries[entry.id]?.submissionPending == true) return@onDisk
		if (!folder(entry).deleteRecursively()) throw StorageFailure()
		entries.remove(entry.id)
		publish()
	}

	suspend fun erase() = onDisk {
		for (folder in root.listFiles().orEmpty()) {
			if (folder.isDirectory && !folder.deleteRecursively()) throw StorageFailure()
		}
		entries.clear()
		publish()
	}

	suspend fun usage(): DownloadDisk = onDisk {
		DownloadDisk(root.walkTopDown().filter(File::isFile).sumOf(File::length), root.parentFile?.usableSpace ?: 0L)
	}

	fun snapshot(entry: DownloadEntry): File = folder(entry).resolve("metadata.json")
	fun decision(entry: DownloadEntry): File = folder(entry).resolve("decision.json")
	fun poster(entry: DownloadEntry): File = folder(entry).resolve("poster.jpg")
	fun subtitle(entry: DownloadEntry, id: Int): File = folder(entry).resolve("subtitle-$id.srt")
	fun partial(entry: DownloadEntry): File = folder(entry).resolve("media.part")
	fun media(entry: DownloadEntry): File = folder(entry).resolve("media")

	private fun folder(entry: DownloadEntry): File = root.resolve(entry.id)

	private fun put(entry: DownloadEntry) {
		storage { write(folder(entry).resolve("entry.json"), CoreJson.encodeToString(DownloadEntry.serializer(), entry)) }
		entries[entry.id] = entry
		publish()
	}

	private fun publish() {
		records.value = entries.values.toList()
	}

	private suspend fun <T> onDisk(block: () -> T): T = withContext(Dispatchers.IO) {
		lock.withLock {
			if (!loaded) {
				val saved = root.listFiles().orEmpty().mapNotNull { folder ->
					if (!folder.isDirectory) return@mapNotNull null
					val record = read(folder.resolve("entry.json")) { CoreJson.decodeFromString<DownloadEntry>(it) }
					// A damaged or unreadable index does not make its media safe to erase.
					record?.takeIf { it.id == folder.name }
				}
				for (entry in saved.sortedBy { it.queuedAt }) entries[entry.id] = entry
				settings.value = read(root.resolve("settings.json")) { CoreJson.decodeFromString<DownloadPreferences>(it) } ?: DownloadPreferences()
				loaded = true
				publish()
			}
			block()
		}
	}

	private fun <T> read(file: File, decode: (String) -> T): T? = try {
		if (file.exists()) decode(file.readText()) else null
	} catch (e: Exception) {
		Diag.warning(DiagCategory.Download, "store.read.failed", "error" to e.javaClass.simpleName)
		null
	}

	companion object {
		fun write(file: File, text: String) {
			val parent = requireNotNull(file.parentFile)
			if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Could not create download directory")
			val pending = file.resolveSibling(file.name + ".new")
			FileOutputStream(pending).use { stream ->
				stream.write(text.encodeToByteArray())
				stream.fd.sync()
			}
			Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
		}
	}
}

internal inline fun <T> storage(block: () -> T): T = try {
	block()
} catch (e: IOException) {
	throw StorageFailure(e)
}

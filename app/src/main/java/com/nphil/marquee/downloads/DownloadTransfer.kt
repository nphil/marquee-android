package com.nphil.marquee.downloads

import android.os.SystemClock
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.MarqueeCore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal class DownloadFailure(message: String) : Exception(message)
internal class TransferStopped : Exception()
internal class TransferResponse(val status: Int) : IOException()

internal fun unconfirmedConversion() = DownloadFailure("Plex may already be preparing this copy. Remove this request to choose Original instead.")

/** Only a strong validator can prove a suffix belongs to the same file. */
@Serializable
@ConsistentCopyVisibility
internal data class DownloadValidator private constructor(val value: String, val resource: String) {
	companion object {
		private val StrongTag = Regex("\"[!#-~]*\"")

		fun resource(address: String): String = address.encodeUtf8().sha256().hex()

		fun read(headers: Headers, resource: String): DownloadValidator? {
			val tags = headers.values("ETag")
			if (tags.isNotEmpty()) {
				return tags.singleOrNull()?.takeIf(StrongTag::matches)?.let { DownloadValidator(it, resource) }
			}
			if (headers.values("Date").size != 1) return null
			val date = headers.getDate("Date")?.time ?: return null
			val modified = headers.getDate("Last-Modified")?.time ?: return null
			// RFC 9110: with no ETag, the origin's Date must be at least 60 seconds newer.
			return headers.values("Last-Modified").singleOrNull()?.takeIf { date - modified >= 60_000 }?.let { DownloadValidator(it, resource) }
		}
	}
}

internal fun downloadRequest(url: String, headers: Map<String, String>): Request.Builder =
	Request.Builder().url(url).headers(Headers.Builder().apply {
		headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) }
	}.build())

/** A validated response describes exactly which bytes can extend the durable local prefix. */
internal data class DownloadRange(val start: Long, val count: Long?, val length: Long?) {
	companion object {
		private val ContentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

		fun read(status: Int, at: Long, contentLength: Long, header: String?): DownloadRange? {
			if (status == 200) return DownloadRange(0, contentLength.takeIf { it >= 0 }, contentLength.takeIf { it >= 0 })
			if (status != 206) return null
			val match = header?.let(ContentRange::matchEntire) ?: return null
			val first = match.groupValues[1].toLongOrNull() ?: return null
			val last = match.groupValues[2].toLongOrNull() ?: return null
			val total = match.groupValues[3].toLongOrNull() ?: return null
			if (first != at || last < first || last >= total) return null
			val count = last - first + 1
			if (contentLength >= 0 && contentLength != count) return null
			return DownloadRange(first, count, total)
		}
	}
}

internal class DownloadTransfer(private val core: MarqueeCore, private val store: DownloadStore, client: OkHttpClient) {
	// An origin change must not forward X-Plex-Token to the redirect's host.
	private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

	suspend fun run(initial: DownloadEntry, server: DownloadServer) {
		if (finishIfComplete(initial)) return
		var entry = initial
		entry.choice.target?.let { entry = convert(entry, server, it) }
		val source = core.downloadSource(server, entry, store.snapshot(entry).path)
		try {
			if (!entry.assetsFetched) {
				val subs = withContext(Dispatchers.IO) {
					source.poster?.let { url ->
						try { asset(url, source.headers, store.poster(entry)) } catch (_: IOException) { /* Artwork is optional. */ }
					}
					source.subtitles.mapNotNull { if (asset(it.url, source.headers, store.subtitle(entry, it.id))) it.id else null }
				}
				entry = keep(entry.id) { it.copy(assetsFetched = true, subtitles = subs) }
			}
			entry = keep(entry.id) { it.copy(phase = DownloadPhase.Downloading) }
			val buffer = ByteArray(64 * 1024)
			val resource = DownloadValidator.resource(source.url)
			while (!finishIfComplete(entry)) {
				if (withContext(Dispatchers.IO) { store.partial(entry).length() < entry.downloaded }) entry = resetBytes(entry.id)
				val next = copy(entry, source, buffer, resource)
				if (next == null) {
					// The file changed or the server no longer accepts the old prefix. Never join the two copies.
					if (entry.downloaded == 0L) throw DownloadFailure("The server returned an invalid download range.")
					entry = resetBytes(entry.id)
				} else {
					entry = next
				}
			}
		} catch (e: TransferResponse) {
			if (e.status != 404 || entry.conversion == null) throw e
			keep(entry.id) { it.copy(restartConversion = true, downloaded = 0, length = null, validator = null) }
			throw IOException("The converted file expired.")
		} catch (e: IOException) {
			core.downloadSourceFailed(server, source.url)
			throw e
		}
	}

	private suspend fun convert(entry: DownloadEntry, server: DownloadServer, target: JsonObject): DownloadEntry {
		val id = entry.id
		val conversion = entry.conversion ?: submit(entry, server, target)
		if (entry.restartConversion) {
			keep(id) { it.copy(downloaded = 0, length = null, validator = null) }
			core.restartConversion(server, conversion)
			keep(id) { it.copy(restartConversion = false) }
		}
		while (true) {
			val state = try {
				core.conversionStatus(server, conversion)
			} catch (e: CoreException) {
				if (e.status != 404) throw e
				// Only a missing queue item, not an error fetching its file, can allow a new submission.
				keep(id) { it.copy(conversion = null, restartConversion = false, downloaded = 0, length = null, validator = null) }
				throw IOException("The server's download queue expired.")
			}
			when (state.status) {
				ConversionPhase.Available -> {
					val decision = core.conversionDecision(server, conversion)
					withContext(Dispatchers.IO) { storage { DownloadStore.write(store.decision(entry), decision) } }
					return keep(id) { it.copy(conversionFraction = null) }
				}
				ConversionPhase.Error, ConversionPhase.Unknown -> {
					keep(id) { it.copy(restartConversion = true) }
					throw DownloadFailure("The server couldn't convert this title.")
				}
				ConversionPhase.Expired -> {
					keep(id) { it.copy(downloaded = 0, length = null, validator = null) }
					core.restartConversion(server, conversion)
				}
				else -> Unit
			}
			keep(id) { it.copy(phase = DownloadPhase.Converting, conversionFraction = state.fraction) }
			delay(5_000)
		}
	}

	private suspend fun submit(entry: DownloadEntry, server: DownloadServer, target: JsonObject): Conversion {
		if (entry.submissionPending) throw unconfirmedConversion()
		val queue = core.downloadQueue(server)
		currentCoroutineContext().ensureActive()
		return withContext(NonCancellable) {
			// The add is not safe to repeat if the response is lost. Commit intent before sending it.
			keep(entry.id) { it.copy(submissionPending = true, phase = DownloadPhase.Converting) }
			val conversion = try {
				core.addConversion(server, queue, entry, target)
			} catch (e: CoreException) {
				if (e.kind == CoreException.Kind.Unauthorized || e.status in RejectedSubmissionStatuses) {
					store.update(entry.id) { it.copy(submissionPending = false) }
					throw e
				}
				throw unconfirmedConversion()
			}
			store.update(entry.id) { it.copy(conversion = conversion, submissionPending = false) }
			conversion
		}
	}

	private suspend fun finishIfComplete(entry: DownloadEntry): Boolean {
		val length = entry.length ?: return false
		if (length <= 0 || entry.downloaded != length) return false
		return withContext(Dispatchers.IO) {
			val file = store.media(entry)
			if (!file.isFile || file.length() != length) {
				val partial = store.partial(entry)
				if (!partial.isFile || partial.length() != length) return@withContext false
				// A restart between the rename and the record update can adopt the finished file above.
				if (!partial.renameTo(file)) throw StorageFailure()
			}
			keep(entry.id) { it.copy(phase = DownloadPhase.Ready, failure = null) }
			true
		}
	}

	private suspend fun resetBytes(id: String): DownloadEntry = keep(id) { it.copy(downloaded = 0, length = null, validator = null) }

	private suspend fun keep(id: String, change: (DownloadEntry) -> DownloadEntry): DownloadEntry {
		currentCoroutineContext().ensureActive()
		return store.update(id) { if (it.phase.active) change(it) else it }
			?.takeIf { it.phase.active || it.phase == DownloadPhase.Ready } ?: throw TransferStopped()
	}

	private suspend fun copy(entry: DownloadEntry, source: DownloadSource, buffer: ByteArray, resource: String): DownloadEntry? {
		val at = if (entry.validator?.resource == resource) entry.downloaded else 0L
		val request = downloadRequest(source.url, source.headers).header("Accept-Encoding", "identity").apply {
			if (at > 0) {
				header("Range", "bytes=$at-")
				header("If-Range", requireNotNull(entry.validator).value)
			}
		}.build()
		return fetch(request) { response ->
			if (response.code == 416) return@fetch null
			if (response.code != 200 && response.code != 206) throw TransferResponse(response.code)
			val body = response.body ?: throw EOFException()
			val subtype = body.contentType()?.subtype
			if (subtype == "json" || subtype == "xml" || subtype == "html" || response.header("Content-Encoding")?.equals("identity", true) == false) {
				throw DownloadFailure("The server didn't return a video file.")
			}
			val range = DownloadRange.read(response.code, at, body.contentLength(), response.header("Content-Range"))
				?: throw DownloadFailure("The server returned an invalid download range.")
			val validator = DownloadValidator.read(response.headers, resource)
			val changedLength = entry.length != null && range.length != entry.length
			if (range.start > 0 && (validator != entry.validator || changedLength)) {
				return@fetch null
			}
			if (validator == null && range.count != null && range.length != range.count) {
				throw DownloadFailure("The server sent only part of the video without a way to verify the rest.")
			}
			val file = store.partial(entry)
			if (range.length != null && range.length > file.parentFile!!.usableSpace + file.length()) throw StorageFailure()
			// Keep the identity and any reset before writing bytes. Only checkpoint data synced to disk.
			var saved = keep(entry.id) { it.copy(downloaded = range.start, length = range.length, validator = validator) }
			var received = 0L
			var lastSave = SystemClock.elapsedRealtime()
			val input = body.source()
			storage { RandomAccessFile(file, "rw") }.use { output ->
				storage {
					output.setLength(range.start)
					output.seek(range.start)
				}
				while (true) {
					currentCoroutineContext().ensureActive()
					val n = input.read(buffer, 0, buffer.size)
					if (n == -1) break
					if (range.count != null && n > range.count - received) throw DownloadFailure("The download was longer than the server said.")
					storage { output.write(buffer, 0, n) }
					received += n
					val now = SystemClock.elapsedRealtime()
					if (now - lastSave >= 2_000 && range.count != received) {
						storage { output.fd.sync() }
						saved = keep(entry.id) { it.copy(downloaded = range.start + received) }
						lastSave = now
					}
				}
				if (range.count == 0L || received == 0L && range.count == null) throw DownloadFailure("The server sent an empty video file.")
				if (range.count != null && received != range.count) throw EOFException()
				storage { output.fd.sync() }
			}
			val end = range.start + received
			if (saved.downloaded != end || saved.length == null) {
				saved = keep(entry.id) { it.copy(downloaded = end, length = range.length ?: end) }
			}
			saved
		}
	}

	private suspend fun asset(url: String, headers: Map<String, String>, file: File): Boolean =
		fetch(downloadRequest(url, headers).build()) { response ->
			if (response.code == 408 || response.code == 429 || response.code in 500..599) throw TransferResponse(response.code)
			if (!response.isSuccessful) return@fetch false
			val source = response.body?.source() ?: return@fetch false
			if (source.request(2L * 1024 * 1024 + 1)) return@fetch false
			val bytes = source.readByteArray()
			if (bytes.isEmpty()) return@fetch false
			storage { file.writeBytes(bytes) }
			true
		}

	/** Cancelling the worker closes a socket that is blocked reading as well as stopping its file writes. */
	private suspend fun <T> fetch(request: Request, read: suspend (Response) -> T): T = coroutineScope {
		val call = http.newCall(request)
		val canceler = launch(start = CoroutineStart.UNDISPATCHED) {
			try { awaitCancellation() } finally { call.cancel() }
		}
		try {
			withContext(Dispatchers.IO) { call.execute().use { read(it) } }
		} finally {
			canceler.cancel()
		}
	}

	private companion object {
		val RejectedSubmissionStatuses = setOf(400, 403, 404, 405, 415, 422)
	}
}

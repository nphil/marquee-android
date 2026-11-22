package com.nphil.marquee.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.core.Capabilities
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.testing.SignedInToFake
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class DownloadRestartTest {
	@get:Rule
	val app = SignedInToFake(film = original)

	@Test
	fun resumingAfterRestartDiscardsUncommittedBytesAndOnlyFetchesTheSuffix() = restarted(ignoresRange = false)

	@Test
	fun anIgnoredRangeReplacesTheWholeFileInsteadOfAppendingIt() = restarted(ignoresRange = true)

	@Test
	fun unvalidatedBytesAreReplacedWithoutRequestingARange() = restarted(ignoresRange = false, validated = false)

	@Test
	fun aDifferentResponseTagDiscardsTheSavedPrefixEvenIfTheServerAnsweredTheRange() =
		restarted(ignoresRange = false, changed = true)

	@Test
	fun corruptIndexLoadingKeepsTheRecordAndEveryLocalFile() = runBlocking {
		val root = File(app.context.cacheDir, "download-corrupt-index-test").apply { deleteRecursively(); mkdirs() }
		try {
			val folder = root.resolve("record").apply { mkdirs() }
			val index = folder.resolve("entry.json").apply { writeText("{incomplete") }
			val partial = folder.resolve("media.part").apply { writeBytes(original) }
			val reopened = DownloadStore(root)
			reopened.load()
			assertTrue(reopened.state.value.isEmpty())
			assertEquals("{incomplete", index.readText())
			assertArrayEquals(original, partial.readBytes())
		} finally {
			root.deleteRecursively()
		}
	}

	@Test
	fun lostConversionReplyIsNotResubmittedAfterRestartOrRemoval() = runBlocking {
		val core = app.container.core
		val server = DownloadServer(app.fake.server, app.fake.remembered)
		val root = File(app.context.cacheDir, "download-unconfirmed-test").apply { deleteRecursively() }
		val path = "/downloadQueue/1/add"
		try {
			val store = DownloadStore(root)
			val offer = core.downloadChoices(server, "101", 0, DownloadQuality.Low, capabilities)
			val converted = offer.options.first { it.id == offer.preferred && it.target != null }
			assertTrue(store.create(scope, offer, 0, converted))
			val entry = store.state.value.single()
			val lostReply = app.fake.client.newBuilder().apply {
				interceptors().add(0) { chain ->
					val request = chain.request()
					val response = chain.proceed(request)
					if (request.url.encodedPath != path) response
					else response.use { throw IOException("The fake Plex accepted the conversion but its reply was lost.") }
				}
			}.build()
			NativeCore.install(NativeTransport(lostReply))
			val first = runCatching { DownloadTransfer(core, store, app.fake.client).run(entry, server) }
			assertTrue(first.exceptionOrNull() is DownloadFailure)
			assertEquals(1, app.fake.requestsTo(path, "POST").size)
			val reopened = DownloadStore(root)
			val pending = requireNotNull(reopened.get(entry.id))
			assertTrue(pending.submissionPending)
			assertNull(pending.conversion)
			val retry = runCatching { DownloadTransfer(core, reopened, app.fake.client).run(pending, server) }
			assertTrue(retry.exceptionOrNull() is DownloadFailure)
			assertEquals(1, app.fake.requestsTo(path, "POST").size)

			reopened.update(pending.id) { it.copy(phase = DownloadPhase.Removed) }
			reopened.discardMedia(pending)
			reopened.forget(pending)
			val afterRemoval = DownloadStore(root)
			assertTrue(runCatching { afterRemoval.create(scope, offer, 0, converted) }.exceptionOrNull() is DownloadFailure)
			assertTrue(afterRemoval.create(scope, offer, 0, offer.options.first { it.target == null }))
		} finally {
			app.fake.install()
			root.deleteRecursively()
		}
	}

	@Test
	fun convertedQualityFetchesTheServerFileAndSavesItsPlaybackDescription() = runBlocking {
		val core = app.container.core
		val server = DownloadServer(app.fake.server, app.fake.remembered)
		val root = File(app.context.cacheDir, "converted-download-test").apply { deleteRecursively() }
		try {
			val store = DownloadStore(root)
			val offer = core.downloadChoices(server, "101", 0, DownloadQuality.Low, capabilities)
			val choice = offer.options.first { it.id == offer.preferred && it.target != null }
			assertTrue(store.create(scope, offer, 0, choice))

			val entry = store.state.value.single()
			DownloadTransfer(core, store, app.fake.client).run(entry, server)

			val ready = requireNotNull(store.get(entry.id))
			val local = requireNotNull(store.playback(ready))
			assertEquals(32L, local.media.length())
			assertTrue(requireNotNull(local.decision).isFile)
			assertEquals(1, app.fake.requestsTo("/downloadQueue/1/add", "POST").size)
			assertEquals(1, app.fake.requestsTo("/downloadQueue/1/item/10/media", "GET").size)
		} finally {
			root.deleteRecursively()
		}
	}

	@Test
	fun offlineWatchSendsTheResumePointButNeverOverwritesNewerPlexHistory() = runBlocking {
		val core = app.container.core
		val server = DownloadServer(app.fake.server, app.fake.remembered)
		val root = File(app.context.cacheDir, "download-watch-test").apply { deleteRecursively() }
		try {
			val store = DownloadStore(root)
			val offer = core.downloadChoices(server, "101", 0, DownloadQuality.Original, capabilities)
			assertTrue(store.create(scope, offer, 0, offer.options.first { it.id == "original" }))
			val entry = store.state.value.single()
			val stale = core.updateDownloadWatch(entry.watch, 30_000, false, 1_700_000_000_000)
			val conflict = core.syncDownloadWatch(server, entry.copy(watch = stale))
			assertNull(conflict.watch.unsynced)
			assertFalse(conflict.changedServer)
			assertEquals(offer.watch.offset, conflict.watch.offset)
			assertTrue(app.fake.requestsTo("/:/timeline").isEmpty())

			val newer = core.updateDownloadWatch(entry.watch, 48_000, false, 1_800_000_000_000)
			val synced = core.syncDownloadWatch(server, entry.copy(watch = newer))
			assertNull(synced.watch.unsynced)
			assertTrue(synced.changedServer)
			assertTrue(app.fake.requestsTo("/:/timeline").single().contains("time=48000"))
		} finally {
			root.deleteRecursively()
		}
	}

	private fun restarted(ignoresRange: Boolean, validated: Boolean = true, changed: Boolean = false) = runBlocking {
		val core = app.container.core
		val server = DownloadServer(app.fake.server, app.fake.remembered)
		val root = File(app.context.cacheDir, "download-test").apply { deleteRecursively() }
		try {
			val store = DownloadStore(root)
			val offer = core.downloadChoices(server, "105", 0, DownloadQuality.Original, capabilities)
			val choice = offer.options.first { it.id == "original" }
			assertTrue(store.create(scope, offer, 0, choice))
			val entry = store.state.value.single()
			val prefix = if (ignoresRange || !validated || changed) ByteArray(checkpoint) { -1 } else original.copyOf(checkpoint)
			store.partial(entry).writeBytes(prefix + ByteArray(8) { -1 })
			val address = core.downloadSource(server, entry, store.snapshot(entry).path).url
			val validator = if (validated) DownloadValidator.read(headersOf("ETag", "\"v1\""), DownloadValidator.resource(address)) else null
			store.update(entry.id) {
				it.copy(downloaded = checkpoint.toLong(), length = original.size.toLong(), validator = validator, assetsFetched = true)
			}

			val reopened = DownloadStore(root)
			val restored = requireNotNull(reopened.get(entry.id))
			assertEquals(checkpoint.toLong(), restored.downloaded)
			val asked = CopyOnWriteArrayList<String>()
			val client = app.fake.client.newBuilder().apply {
				interceptors().add(0) { chain ->
					val request = chain.request()
					val range = request.header("Range")
					asked += range.orEmpty()
					if (range != null) assertEquals("\"v1\"", request.header("If-Range"))
					val sent = if (ignoresRange) request.newBuilder().removeHeader("Range").build() else request
					val tag = if (changed) "\"v2\"" else "\"v1\""
					chain.proceed(sent).newBuilder().header("ETag", tag).build()
				}
			}.build()
			DownloadTransfer(core, reopened, client).run(restored, server)
			val ready = requireNotNull(reopened.get(entry.id))
			val expected = when {
				!validated -> listOf("")
				changed -> listOf("bytes=$checkpoint-", "")
				else -> listOf("bytes=$checkpoint-")
			}
			assertEquals(expected, asked.toList())
			assertEquals(DownloadPhase.Ready, ready.phase)
			assertFalse(reopened.partial(ready).exists())
			val playback = requireNotNull(reopened.playback(ready))
			assertArrayEquals(original, playback.media.readBytes())
		} finally {
			root.deleteRecursively()
		}
	}

	private companion object {
		val capabilities = Capabilities(false, false, false, false, emptyList(), listOf("aac"), listOf("mp4"))
		val scope = DownloadScope("fake-server", "profile")
		val original = ByteArray(96) { it.toByte() }
		const val checkpoint = 32
	}
}

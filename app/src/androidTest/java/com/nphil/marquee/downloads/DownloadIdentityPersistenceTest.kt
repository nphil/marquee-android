package com.nphil.marquee.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nphil.marquee.core.Capabilities
import com.nphil.marquee.testing.SignedInToFake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class DownloadIdentityPersistenceTest {
	@get:Rule val app = SignedInToFake()

	@Test
	fun deletionKeepsTheOriginalTitleAndPendingRewatchUntilSync() = runBlocking {
		val root = Files.createTempDirectory(app.context.cacheDir.toPath(), "download-lineage-").toFile()
		try {
			val store = DownloadStore(root)
			val server = DownloadServer(app.fake.server, app.fake.remembered)
			val capabilities = Capabilities(false, false, false, false, emptyList(), listOf("aac"), listOf("mp4"))
			val offer = app.container.core.downloadChoices(server, "101", 0, DownloadQuality.Original, capabilities)
			assertEquals("plex://movie/62a1b2c3d4e5f60718000101", offer.title.guid)
			val scope = requireNotNull(app.container.session.state.value.downloadScope)
			assertTrue(store.create(scope, offer, 0, offer.options.first { it.id == "original" }))
			val entry = store.state.value.single()
			val watch = DownloadWatch(48_000, true, unsynced = 700_000_000.0, finishedHere = true, scrobble = ScrobbleReceipt(2, 700_000_100.0))
			val removed = requireNotNull(store.update(entry.id) { it.copy(phase = DownloadPhase.Removed, watch = watch) })
			store.discardMedia(removed)

			val restored = requireNotNull(DownloadStore(root).get(entry.id))
			assertFalse(store.snapshot(restored).exists())
			assertEquals(DownloadPhase.Removed, restored.phase)
			assertEquals(offer.title.guid, restored.title.guid)
			assertEquals(scope, restored.scope)
			assertEquals(watch, restored.watch)
		} finally {
			root.deleteRecursively()
		}
	}
}

package com.nphil.marquee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.DeleteOutcome
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.WatchlistCleanup
import com.nphil.marquee.core.addToWatchlist
import com.nphil.marquee.core.children
import com.nphil.marquee.core.copiesOf
import com.nphil.marquee.core.delete
import com.nphil.marquee.core.deleteVersion
import com.nphil.marquee.core.extras
import com.nphil.marquee.core.item
import com.nphil.marquee.core.mediaUrl
import com.nphil.marquee.core.onWatchlist
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.related
import com.nphil.marquee.core.removeFromWatchlist
import com.nphil.marquee.core.serverInfo
import com.nphil.marquee.core.setWatched
import com.nphil.marquee.fakeplex.FakePlexServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detail page's bridge calls (DetailCore.kt) through the real Swift core, over the fake Plex of
 * core/UITests/FakePlex: every request Core makes is answered from the fixtures by [FakePlexServer].
 */
@RunWith(AndroidJUnit4::class)
class DetailBridgeTest {
	private lateinit var fake: FakePlexServer
	private val core = MarqueeCore {
		ClientIdentity("detail-bridge-test", "Marquee", "test", "Android", "test", "emulator", "emulator")
	}

	private val server get() = fake.server
	private val at get() = fake.remembered

	@Before
	fun start() {
		fake = FakePlexServer.start(InstrumentationRegistry.getInstrumentation().context)
		fake.install()
		runBlocking { core.reset() }
	}

	@After
	fun stop() = fake.stop()

	private fun item(key: String) = runBlocking { core.item(server, at, key) }

	@Test
	fun elephantsDreamHasATrailerAmongItsExtras() = runBlocking {
		val extras = core.extras(server, at, "105")
		val trailer = extras.single()
		assertEquals("9105", trailer.ratingKey)
		assertEquals(MediaType.Clip, trailer.type)
		assertEquals("trailer", trailer.subtype)
		assertEquals(1, trailer.extraType)
		assertEquals("/library/parts/91051/1/trailer.mp4", trailer.media.single().parts.single().key)
		assertEquals("Elephants Dream", item("105").title)
	}

	@Test
	fun sintelIsHalfWatchedWithTwoVersionsAndTheirTracks() {
		val sintel = item("108")
		assertEquals("Sintel", sintel.title)
		assertFalse(sintel.isWatched)
		assertEquals(355_000, sintel.viewOffset)
		val progress = sintel.progress
		assertNotNull(progress)
		assertTrue("progress $progress", progress!! > 0f && progress < 1f)

		assertEquals(listOf(1081, 1082), sintel.media.map { it.id })
		assertEquals("4k", sintel.media[0].videoResolution)
		assertEquals("1080", sintel.media[1].videoResolution)

		val tracks = sintel.media[0].parts.single().tracks!!
		assertEquals(listOf(30812, 30813), tracks.audio.map { it.id })
		assertTrue(tracks.audio.joinToString { it.label }, tracks.audio.all { it.label.startsWith("English") })
		assertTrue(tracks.audio[0].isDefault)
		assertEquals(8, tracks.audio[0].channels)
		assertEquals(listOf(30814, 30815, 30816), tracks.subtitles.map { it.id })
		assertTrue(tracks.subtitles.joinToString { it.label }, tracks.subtitles.take(2).all { it.label.startsWith("English") })
		assertTrue(tracks.subtitles[2].label, tracks.subtitles[2].label.let { "Spanish" in it || "Espa" in it })
		assertNull(tracks.subtitles[0].imageFormat)
		assertEquals("PGS", tracks.subtitles[1].imageFormat)
		assertNotNull(sintel.media[1].parts.single().tracks)
	}

	@Test
	fun caminandesListsSeasonsAndTheSecondEpisodeIsInProgress() = runBlocking {
		val show = core.item(server, at, "201")
		assertEquals(MediaType.Show, show.type)
		assertEquals(3, show.leafCount)

		val seasons = core.children(server, at, "201")
		assertEquals(listOf("Season 1", "Season 2"), seasons.map { it.title })
		assertTrue(seasons.all { it.type == MediaType.Season })

		val episodes = core.children(server, at, seasons.first().ratingKey)
		assertEquals(listOf("211", "212", "213"), episodes.map { it.ratingKey })
		assertTrue(episodes.all { it.type == MediaType.Episode })
		assertTrue(episodes[0].isWatched)
		val progress = episodes.single { it.ratingKey == "212" }.progress
		assertNotNull(progress)
		assertTrue("progress $progress", progress!! > 0f && progress < 1f)
		assertNull(episodes[0].progress)
		assertNull(episodes[2].progress)
	}

	@Test
	fun markingWatchedAndUnwatchedIsWrittenToThePlexServerAndReadBack() = runBlocking {
		assertFalse(item("108").isWatched)

		core.setWatched(server, at, "108", watched = true)
		assertEquals(1, fake.requestsTo("/:/scrobble", method = "GET", host = "192.0.2.10").size)
		assertTrue(fake.requestsTo("/:/scrobble").single().contains("key=108"))
		val watched = item("108")
		assertTrue(watched.isWatched)
		assertEquals(0, watched.viewOffset)
		assertNull(watched.progress)

		core.setWatched(server, at, "108", watched = false)
		assertTrue(fake.requestsTo("/:/unscrobble").single().contains("key=108"))
		val unwatched = item("108")
		assertFalse(unwatched.isWatched)
		assertEquals(0, unwatched.viewCount)

		// Elephants Dream starts watched, and a mark on one title leaves the others alone.
		assertTrue(item("105").isWatched)
	}

	@Test
	fun relatedTitlesDecodeAsShelves() = runBlocking {
		val shelves = core.related(server, at, "108")
		val similar = shelves.single()
		assertEquals("external.similar", similar.identifier)
		assertEquals(similar.items.size, similar.size)
		assertEquals(4, similar.items.size)
		assertTrue(similar.items.all { it.ratingKey.isNotEmpty() && it.title.isNotEmpty() })
		assertTrue(core.related(server, at, "105").isEmpty())
	}

	@Test
	fun aCatalogueGuidFindsTheServersOwnCopy() = runBlocking {
		val copies = core.copiesOf(server, at, "plex://movie/62a1b2c3d4e5f60718000105")
		assertEquals(listOf("105"), copies.map { it.ratingKey })
	}

	@Test
	fun theServerAllowsDeletingMedia() = runBlocking {
		assertTrue(core.serverInfo(server, at).allowsMediaDeletion)
	}

	@Test
	fun deletingATitleOrOneVersionSendsDeleteAndIsDeleted() = runBlocking {
		val sintel = item("108")
		val whole = core.delete(server, at, sintel, alreadyDeleted = emptySet(), accountToken = null)
		assertEquals(DeleteOutcome.Deleted, whole.outcome)
		assertEquals(WatchlistCleanup.Unchanged, whole.watchlist)
		assertEquals(1, fake.requestsTo("/library/metadata/108", method = "DELETE").size)

		val version = core.deleteVersion(server, at, "108", mediaId = 1082)
		assertEquals(DeleteOutcome.Deleted, version.outcome)
		assertEquals(1, fake.requestsTo("/library/metadata/108/media/1082", method = "DELETE").size)

		// The fixtures keep a deleted title, as a server lists one until its trash is emptied.
		assertEquals("Sintel", item("108").title)
	}

	@Test
	fun aMediaAddressCarriesNoTokenAndTheHeadersDo() = runBlocking {
		val media = core.mediaUrl(server, at, "/library/parts/2081/file.mkv")
		assertEquals("${FakePlexServer.SERVER_URL}/library/parts/2081/file.mkv", media.url)
		assertFalse(media.url, media.url.contains("X-Plex-Token", ignoreCase = true))
		assertEquals(FakePlexServer.SERVER_TOKEN, media.headers.entries.single { it.key.equals("X-Plex-Token", ignoreCase = true) }.value)
	}

	@Test
	fun theWatchlistAnswersAndTakesWrites() = runBlocking {
		val watchlisted = listOf(
			"plex://movie/62a1b2c3d4e5f60718000112" to "Wing It!",
			"plex://movie/62a1b2c3d4e5f60718000109" to "Spring",
			"plex://show/62a1b2c3d4e5f60718000201" to "Caminandes",
			"plex://movie/62a1b2c3d4e5f60718000104" to "Cosmos Laundromat",
			"plex://movie/62a1b2c3d4e5f60718000105" to "Elephants Dream",
		)
		for ((guid, title) in watchlisted) assertTrue(title, core.onWatchlist(fake.token, guid))
		assertFalse(core.onWatchlist(fake.token, "plex://movie/62a1b2c3d4e5f60718000101"))

		val sintel = "plex://movie/62a1b2c3d4e5f60718000108"
		core.addToWatchlist(fake.token, sintel)
		val added = fake.requestsTo("/actions/addToWatchlist", method = "PUT", host = "discover.provider.plex.tv").single()
		assertTrue(added, added.contains("ratingKey=62a1b2c3d4e5f60718000108"))

		core.removeFromWatchlist(fake.token, watchlisted[4].first)
		val removed = fake.requestsTo("/actions/removeFromWatchlist", method = "PUT", host = "discover.provider.plex.tv").single()
		assertTrue(removed, removed.contains("ratingKey=62a1b2c3d4e5f60718000105"))
	}
}

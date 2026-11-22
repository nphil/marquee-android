package com.nphil.marquee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrLink
import com.nphil.marquee.core.seerrDetails
import com.nphil.marquee.core.seerrDiscover
import com.nphil.marquee.core.seerrParse
import com.nphil.marquee.core.seerrRequest
import com.nphil.marquee.core.seerrRequests
import com.nphil.marquee.core.seerrSearch
import com.nphil.marquee.core.seerrSignIn
import com.nphil.marquee.core.seerrVersion
import com.nphil.marquee.fakeplex.FakePlexServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Seerr bridge calls (SeerrCore.kt) through the real Swift core, over the fake Seerr of
 * core/UITests/FakePlex/seerr: Core signs in with an API key, finds a title and requests it.
 */
@RunWith(AndroidJUnit4::class)
class SeerrBridgeTest {
	private lateinit var fake: FakePlexServer
	private val core = MarqueeCore {
		ClientIdentity("seerr-bridge-test", "Marquee", "test", "Android", "test", "emulator", "emulator")
	}
	private val link = SeerrLink("http://seerr.local:5055", apiKey = "key", plexToken = null, session = null)

	@Before
	fun start() {
		fake = FakePlexServer.start(InstrumentationRegistry.getInstrumentation().context)
		fake.install()
		runBlocking { core.reset() }
	}

	@After
	fun stop() = fake.stop()

	@Test
	fun parseNamesWhatIsWrongWithAnAddress() = runBlocking {
		assertNull(core.seerrParse("https://seerr.local", null).problem)
		assertEquals("scheme", core.seerrParse("ftp://seerr.local", null).problem)
		assertEquals("key", core.seerrParse("https://seerr.local", " ").problem)
	}

	@Test
	fun anApiKeySignsInAndSeerrNamesItsVersionAndUser() = runBlocking {
		assertEquals("2.0.0", core.seerrVersion(link).value)
		val user = core.seerrSignIn(link)
		assertEquals("Jordan", user.value.name)
		assertNull(user.session)
	}

	@Test
	fun aTitleIsFoundAndRequested() = runBlocking {
		val found = core.seerrSearch(link, "Wing").value
		assertEquals(listOf(9101), found.map { it.tmdbId })

		val before = core.seerrDetails(link, SeerrKind.Movie, 9101, pulsarr = false, serverId = null).value
		assertTrue(before.canRequest)

		core.seerrRequest(link, SeerrKind.Movie, 9101, seasons = emptySet(), is4K = false)

		val after = core.seerrDetails(link, SeerrKind.Movie, 9101, pulsarr = false, serverId = null).value
		assertFalse(after.canRequest)
		assertEquals(listOf(502, 501), core.seerrRequests(link).value.map { it.id })
	}

	@Test
	fun theTrendingShelfHoldsTitles() = runBlocking {
		assertTrue(core.seerrDiscover(link, "trending").value.isNotEmpty())
	}
}

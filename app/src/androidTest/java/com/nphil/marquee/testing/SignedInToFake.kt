package com.nphil.marquee.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.AppContainer
import com.nphil.marquee.container
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.data.BrowsePreferences
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.fakeplex.FakePlexServer
import com.nphil.marquee.fakeplex.router.Fixtures
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.session.SavedPlexSignIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource
import java.io.IOException

private const val SignInMillis = 20_000L

/** The file the fixtures would hold for Elephants Dream's trailer; they hold none. */
private const val TrailerPart = "pms/library/parts/91051/1/trailer.mp4.bin"

/** The file the fixtures would hold for Elephants Dream itself; they hold none either. */
private const val FilmPart = "pms/library/parts/1051/file.mp4.bin"

/**
 * The real app container signed in to the fake Plex of core/UITests/FakePlex the way a returning person is, with its
 * artwork served by the fake too. A test that renders the real screens takes it as a rule and shows
 * [com.nphil.marquee.ui.MarqueeApp] over [container].
 *
 * The fixtures have no trailer file, and without one the server answers the player with filler it cannot play.
 * [trailer], an androidTest asset such as `trailers/scope.mp4`, is the file Elephants Dream's trailer plays, and [film]
 * the bytes the movie itself plays.
 */
class SignedInToFake(private val trailer: String? = null, private val film: ByteArray? = null) : ExternalResource() {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	val context: Context = instrumentation.targetContext
	val container: AppContainer get() = context.container

	lateinit var fake: FakePlexServer
		private set
	lateinit var art: FakeArt
		private set

	override fun before() {
		fake = if (trailer == null && film == null) FakePlexServer.start(instrumentation.context) else FakePlexServer.start(fixturesWith())
		fake.install()
		runBlocking { container.core.reset() }
		SavedPlexSignIn(fake.token, listOf(fake.server), fake.profile).save(SecretStore(context))
		runBlocking { container.session.restore() }
		val phase = container.session.state.value.phase
		check(phase == AppSession.Phase.Ready) { "the fake server did not sign in: $phase" }
		art = FakeArt(context, fake.client).also { it.install() }
	}

	override fun after() {
		art.restore()
		// The next test seeds the same store, so finish clearing it before leaving this rule.
		runBlocking { container.session.signOut().join() }
		fake.stop()
		// The app's own transport again, as the container left it.
		NativeCore.install(NativeTransport(container.http))
	}

	/**
	 * Sets what the person would have set. [block] runs against the preferences with the id of the profile browsing
	 * now, which is what they are kept under.
	 */
	fun prefer(block: suspend BrowsePreferences.(profile: String) -> Unit) = runBlocking {
		val browse = container.browse
		val place = withTimeout(SignInMillis) { browse.source.place.filterNotNull().first() }
		browse.preferences.block(place.profile)
	}

	private fun fixturesWith(): Fixtures {
		val assets = instrumentation.context.assets
		return Fixtures { path ->
			if (path == FilmPart && film != null) {
				film
			} else {
				try {
					assets.open(if (path == TrailerPart) trailer ?: path else path).use { it.readBytes() }
				} catch (_: IOException) {
					null
				}
			}
		}
	}
}

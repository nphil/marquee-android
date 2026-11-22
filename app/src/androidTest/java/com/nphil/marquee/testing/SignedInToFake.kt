package com.nphil.marquee.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.nphil.marquee.AppContainer
import com.nphil.marquee.container
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.data.BrowsePreferences
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.fakeplex.FakePlexServer
import com.nphil.marquee.fakeplex.router.Fixtures
import com.nphil.marquee.session.AppSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.rules.ExternalResource
import java.io.IOException

private const val SignInMillis = 20_000L

/** The file the fixtures would hold for Elephants Dream's trailer; they hold none. */
private const val TrailerPart = "pms/library/parts/91051/1/trailer.mp4.bin"

/**
 * The real app container signed in to the fake Plex of core/UITests/FakePlex the way a returning person is, with its
 * artwork served by the fake too. A test that renders the real screens takes it as a rule and shows
 * [com.nphil.marquee.ui.MarqueeApp] over [container].
 *
 * The fixtures have no trailer file, and without one the server answers the player with filler it cannot play.
 * [trailer], an androidTest asset such as `trailers/scope.mp4`, is the file Elephants Dream's trailer plays.
 */
class SignedInToFake(private val trailer: String? = null) : ExternalResource() {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	val context: Context = instrumentation.targetContext
	val container: AppContainer get() = context.container

	lateinit var fake: FakePlexServer
		private set
	lateinit var art: FakeArt
		private set

	override fun before() {
		fake = if (trailer == null) FakePlexServer.start(instrumentation.context) else FakePlexServer.start(fixturesWith(trailer))
		fake.install()
		runBlocking { container.core.reset() }
		// What a returning person has kept: AppSession's own TOKEN and SERVERS entries.
		SecretStore(context).run {
			write("plex.token", fake.token)
			write("plex.servers", CoreJson.encodeToString(ListSerializer(PlexServer.serializer()), listOf(fake.server)))
		}
		runBlocking { container.session.restore() }
		val phase = container.session.state.value.phase
		check(phase == AppSession.Phase.Ready) { "the fake server did not sign in: $phase" }
		art = FakeArt(context, fake.client).also { it.install() }
	}

	override fun after() {
		art.restore()
		container.session.signOut()
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

	private fun fixturesWith(trailer: String): Fixtures {
		val assets = instrumentation.context.assets
		return Fixtures { path ->
			try {
				assets.open(if (path == TrailerPart) trailer else path).use { it.readBytes() }
			} catch (_: IOException) {
				null
			}
		}
	}
}

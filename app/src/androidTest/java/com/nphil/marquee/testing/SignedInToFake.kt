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
import com.nphil.marquee.session.AppSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.rules.ExternalResource

private const val SignInMillis = 20_000L

/**
 * The real app container signed in to the fake Plex of core/UITests/FakePlex the way a returning person is, with its
 * artwork served by the fake too. A test that renders the real screens takes it as a rule and shows
 * [com.nphil.marquee.ui.MarqueeApp] over [container].
 */
class SignedInToFake : ExternalResource() {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	val context: Context = instrumentation.targetContext
	val container: AppContainer get() = context.container

	lateinit var fake: FakePlexServer
		private set
	lateinit var art: FakeArt
		private set

	override fun before() {
		fake = FakePlexServer.start(instrumentation.context)
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
}

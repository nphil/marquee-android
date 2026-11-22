package com.nphil.marquee.profile

import com.nphil.marquee.AppContainer
import com.nphil.marquee.MarqueeApplication
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.diagnostics.DiagRequestLog
import com.nphil.marquee.fakeplex.FakePlexServer
import com.nphil.marquee.session.SavedPlexSignIn
import kotlin.concurrent.thread

/**
 * The app as the baseline profile generators run it: every request, Core's and the artwork's, is answered from the
 * iPhone app's fixtures (core/UITests/FakePlex), and every start is signed in to the fixture account, so a journey
 * opens on Home with no Plex account and no network. Only the nonMinifiedRelease build has this class; the shipped
 * build is wired by [MarqueeApplication] alone (the manifest's application name is a placeholder, see build.gradle.kts).
 */
class ProfilingApplication : MarqueeApplication() {
	// The mock server resolves "localhost", which the main thread may not do.
	private val fake: FakePlexServer by lazy {
		lateinit var started: FakePlexServer
		thread { started = FakePlexServer.start(this) }.join()
		started
	}

	override fun createContainer(): AppContainer {
		SavedPlexSignIn(fake.token, listOf(fake.server), fake.profile).save(SecretStore(this))
		return AppContainer(this, fake.client.newBuilder().addInterceptor(DiagRequestLog()).build())
	}
}

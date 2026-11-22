package com.nphil.marquee

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.SessionBrowseSource
import com.nphil.marquee.browse.Snapshots
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.LibraryChanges
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.data.AppSettings
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.data.SubtitleServices
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagRequestLog
import com.nphil.marquee.downloads.Downloads
import com.nphil.marquee.live.LiveTv
import com.nphil.marquee.playback.PlaybackCoordinator
import com.nphil.marquee.seerr.Seerr
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ToastCenter
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucentTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * The app's single instances, wired by hand: there is one of each. Only the HTTP client can be handed in, which the
 * build the baseline profile is recorded against uses to answer from fixtures.
 */
class AppContainer(
	context: Context,
	val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(DiagRequestLog()).build(),
) {
	val scope: CoroutineScope = MainScope()
	val settings = AppSettings(context)
	private val identityOnce = scope.async(start = CoroutineStart.LAZY) { identity(context) }
	val core = MarqueeCore { identityOnce.await() }
	val session = AppSession(core, SecretStore(context), settings, scope)

	/** Requests: off until Settings › Integrations › Seerr turns it on. Its own secrets survive a Plex sign-out. */
	val seerr = Seerr(core, settings, SecretStore(context, "seerr"), session, scope)
	internal val subtitleServices = SubtitleServices(settings, SecretStore(context, "subtitle_services"), scope)

	/** What this app changed on the server, so every screen showing a changed title refreshes it. */
	val changes = LibraryChanges()
	val toasts = ToastCenter(scope)
	internal val downloads = Downloads(context, core, session, changes, toasts, scope, http)

	/** What Home, Library, Search and the Watchlist share: the server and profile browsed, their preferences and saved answers. */
	val browse = BrowseEnvironment(
		SessionBrowseSource(session, core, scope), settings, changes, toasts, Snapshots.directory(context.cacheDir), scope,
	)
	internal val live = LiveTv(this, context.cacheDir.resolve("live-tv"))

	/** What the trailer, the theme tune and the extras viewer tell each other (who is sounding, whether a player is open). */
	val playback = PlaybackCoordinator()
	val themes by lazy {
		Themes(context, ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() }))
	}
	val typography by lazy { lucentTypography(context.assets) }

	init {
		NativeCore.install(NativeTransport(http))
		scope.launch { session.restore() }
		scope.launch { session.state.map { it.user?.title }.distinctUntilChanged().collect { Diag.profile(it) } }
		scope.launch { Snapshots.forgetWhenLeft(session, core, browse.snapshots) }
	}

	/** What this tablet tells Plex about itself, made once. */
	suspend fun clientIdentity(): ClientIdentity = identityOnce.await()

	/** What Plex shows for this tablet in its device list. */
	private suspend fun identity(context: Context) = ClientIdentity(
		clientIdentifier = settings.clientIdentifier(),
		product = "Marquee",
		version = BuildConfig.VERSION_NAME,
		platform = "Android",
		platformVersion = Build.VERSION.RELEASE,
		device = Build.MODEL,
		deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL,
	)
}

open class MarqueeApplication : Application(), SingletonImageLoader.Factory {
	lateinit var container: AppContainer
		private set

	override fun onCreate() {
		super.onCreate()
		Diag.install(this)
		container = createContainer()
	}

	/** The one place a build other than the shipped one changes how the app is wired; see src/nonMinifiedRelease. */
	protected open fun createContainer() = AppContainer(this)

	/**
	 * Profile pictures and Plex artwork come over the app's own client. Artwork is resized by the server to the
	 * pixels a view needs, so 256 MB on disk holds several thousand posters, and a restart or a dropped
	 * connection still shows them.
	 */
	override fun newImageLoader(context: PlatformContext): ImageLoader =
		ImageLoader.Builder(context)
			.components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
			.diskCache {
				DiskCache.Builder()
					.directory(context.cacheDir.resolve("art").toOkioPath())
					.maxSizeBytes(256L * 1024 * 1024)
					.build()
			}
			.build()
}

val Context.container: AppContainer get() = (applicationContext as MarqueeApplication).container

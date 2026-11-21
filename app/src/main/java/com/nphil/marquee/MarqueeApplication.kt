package com.nphil.marquee

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.core.NativeTransport
import com.nphil.marquee.data.AppSettings
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.theme.ThemeCatalog
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucentTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The app's single instances, wired by hand: there is one of each and nothing to swap. */
class AppContainer(context: Context) {
	val scope: CoroutineScope = MainScope()
	val http = OkHttpClient()
	val settings = AppSettings(context)
	val core = MarqueeCore { identity(context) }
	val session = AppSession(core, SecretStore(context), settings, scope)
	val themes by lazy {
		Themes(context, ThemeCatalog.parse(context.assets.open("themes.json").use { it.readBytes().decodeToString() }))
	}
	val typography by lazy { lucentTypography(context.assets) }

	init {
		NativeCore.install(NativeTransport(http))
		scope.launch { session.restore() }
	}

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

class MarqueeApplication : Application(), SingletonImageLoader.Factory {
	lateinit var container: AppContainer
		private set

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this)
	}

	/** Profile pictures come from plex.tv over the app's own client. */
	override fun newImageLoader(context: PlatformContext): ImageLoader =
		ImageLoader.Builder(context)
			.components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
			.build()
}

val Context.container: AppContainer get() = (applicationContext as MarqueeApplication).container

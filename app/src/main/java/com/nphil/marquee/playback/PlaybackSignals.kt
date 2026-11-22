package com.nphil.marquee.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.nphil.marquee.container

/**
 * Whether other audio is playing, from the number of players the system lists as sounding and how many of them are
 * this app's own.
 *
 * Android has no flag for "another app is playing". `AudioManager.isMusicActive` is also true for the app's own
 * players, and a playback configuration carries no owner a normal app may read, so neither can tell the app's theme
 * tune from someone else's music. The count can: every sounding player is listed, the app knows how many of its
 * own are sounding ([PlaybackCoordinator.ownAudio]), and any more are another app's.
 */
object OtherAudio {
	/**
	 * Usages that are heard as music, a podcast, a game or a call. Notifications, alarms and navigation prompts are
	 * short and not what a trailer should stay quiet for.
	 */
	private val heard = setOf(
		AudioAttributes.USAGE_UNKNOWN,
		AudioAttributes.USAGE_MEDIA,
		AudioAttributes.USAGE_VOICE_COMMUNICATION,
		AudioAttributes.USAGE_GAME,
		AudioAttributes.USAGE_ASSISTANT,
	)

	/** How many of the sounding players, given as their usages, are heard as media. */
	fun heardPlayers(usages: List<Int>): Int = usages.count { it in heard }

	/** More heard players than the app has sounding means another app's is among them. */
	fun playing(heardPlayers: Int, own: Int): Boolean = heardPlayers > own
}

/**
 * What the tablet is doing that decides whether a trailer or the theme tune may play (battery saver, animations,
 * a metered connection, other audio, whether the app is in front), as snapshot state. Reading a value in a composable
 * or a `derivedStateOf` recomposes only when it changes; nothing here runs per frame. The system is listened to
 * only while [rememberPlaybackSignals] is in the composition.
 */
@Stable
class PlaybackSignals internal constructor(
	private val coordinator: PlaybackCoordinator,
	private val lifecycle: State<Lifecycle.State>,
) {
	/** Battery Saver is on. */
	var powerSaver by mutableStateOf(false)
		internal set

	/** The system's animator duration scale is 0 (Settings › Developer options, or Remove animations). */
	var reduceMotion by mutableStateOf(false)
		internal set

	/** The active network is metered: mobile data, or Wi-Fi marked as metered such as a hotspot. */
	var metered by mutableStateOf(false)
		internal set

	/** The app is in front: its lifecycle is resumed. */
	val appActive: Boolean get() = lifecycle.value.isAtLeast(Lifecycle.State.RESUMED)

	private var heardPlayers by mutableIntStateOf(0)

	/** Another app's audio is playing. */
	val otherAudio: Boolean get() = OtherAudio.playing(heardPlayers, coordinator.ownAudio)

	internal fun hear(usages: List<Int>) {
		heardPlayers = OtherAudio.heardPlayers(usages)
	}
}

/** The [PlaybackSignals] for this composition, kept current while it is in the composition. */
@Composable
fun rememberPlaybackSignals(): PlaybackSignals {
	val context = LocalContext.current
	val coordinator = context.container.playback
	val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
	val signals = remember(coordinator, lifecycle) { PlaybackSignals(coordinator, lifecycle) }

	DisposableEffect(context, signals) {
		val app = context.applicationContext
		// Every callback below runs on the main thread, so every write to the signals does too.
		val main = Handler(Looper.getMainLooper())
		val power = app.getSystemService(PowerManager::class.java)
		val connectivity = app.getSystemService(ConnectivityManager::class.java)
		val audio = app.getSystemService(AudioManager::class.java)

		signals.powerSaver = power.isPowerSaveMode
		val powerReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				signals.powerSaver = power.isPowerSaveMode
			}
		}
		ContextCompat.registerReceiver(
			app, powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
		)

		fun readScale() = Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
		signals.reduceMotion = readScale()
		val scaleObserver = object : ContentObserver(main) {
			override fun onChange(selfChange: Boolean) {
				signals.reduceMotion = readScale()
			}
		}
		app.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver)

		signals.metered = connectivity.isActiveNetworkMetered
		val network = object : ConnectivityManager.NetworkCallback() {
			override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
				signals.metered = connectivity.isActiveNetworkMetered
			}

			override fun onLost(network: Network) {
				signals.metered = connectivity.isActiveNetworkMetered
			}
		}
		connectivity.registerDefaultNetworkCallback(network, main)

		fun usages(configs: List<AudioPlaybackConfiguration>) = configs.map { it.audioAttributes.usage }
		signals.hear(usages(audio.activePlaybackConfigurations))
		val playback = object : AudioManager.AudioPlaybackCallback() {
			override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
				signals.hear(usages(configs))
			}
		}
		audio.registerAudioPlaybackCallback(playback, main)

		onDispose {
			app.unregisterReceiver(powerReceiver)
			app.contentResolver.unregisterContentObserver(scaleObserver)
			connectivity.unregisterNetworkCallback(network)
			audio.unregisterAudioPlaybackCallback(playback)
		}
	}
	return signals
}

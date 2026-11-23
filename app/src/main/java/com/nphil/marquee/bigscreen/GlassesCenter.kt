package com.nphil.marquee.bigscreen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.view.Display
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.data.BigScreenPrefs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.PlayerModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A title the big screen asked the phone's player to open. */
internal class PlayRequest(val ratingKey: String, val titleKey: String, val fromStart: Boolean)

/**
 * The big-screen plugin: XR glasses and TVs (DESIGN §8.9). Glasses such as the Viture Pro, or a TV on USB-C video out
 * or an HDMI adapter, reach the phone as an external display, which Android offers to apps as a presentation display.
 * With the plugin on, the first connection of a display asks whether to use Marquee on it; if so it gets Marquee's own
 * screen and the phone becomes an eyes-free remote. Otherwise the system treats the display as it does with the
 * plugin off. Everything here runs on the main thread.
 */
@Stable
internal class GlassesCenter(private val container: AppContainer, private val scope: CoroutineScope) {
	var prefs by mutableStateOf(BigScreenPrefs())
		private set

	/** The connected display, while the system has one for presentations and it is not left to mirror. */
	var display: DisplayIdentity? by mutableStateOf(null)
		private set

	/** What the display is, once decided: the remembered answer or the page's choice. */
	var kind: DisplayKind? by mutableStateOf(null)
		private set

	/** Marquee's screen is up and the remote drives it. */
	var isConnected by mutableStateOf(false)
		private set

	/** The phone app was chosen over the remote; the display button brings the remote back. */
	var remoteHidden by mutableStateOf(false)
		private set

	/** "Use Marquee on this display?" waits for an answer. */
	var asksToUse by mutableStateOf(false)
		private set

	/** Said once, the first time the person puts the remote away. */
	var leaveHint: String? by mutableStateOf(null)
		private set
	var navigator: GlassesNavigator? by mutableStateOf(null)
		private set

	/** The system's media volume while connected, 0 to 1, and a count of its changes so the bar can show for a moment. */
	var volume by mutableFloatStateOf(0f)
		private set
	var volumeStamp by mutableIntStateOf(0)
		private set

	private val requests = MutableSharedFlow<PlayRequest>(extraBufferCapacity = 1)
	val plays: SharedFlow<PlayRequest> = requests.asSharedFlow()

	private val settingsOpens = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The remote's gear: the phone app comes back on its Settings. */
	val settingsRequests: SharedFlow<Unit> = settingsOpens.asSharedFlow()

	private var activity: Activity? = null
	private var manager: DisplayManager? = null
	private var audio: AudioManager? = null
	private var presentation: GlassesPresentation? = null
	private var shownDisplayId = -1
	private var mirroring = false
	private var loaded = false

	private val listener = object : DisplayManager.DisplayListener {
		override fun onDisplayAdded(displayId: Int) = refresh()
		override fun onDisplayRemoved(displayId: Int) = refresh()
		override fun onDisplayChanged(displayId: Int) {
			if (displayId == shownDisplayId) refresh()
		}
	}

	private val volumeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) showVolume(level())
		}
	}

	init {
		scope.launch { container.settings.bigScreen.collect { saved -> adopt(saved) } }
	}

	/** The remote's button and the screen's title: what the display is, once known. */
	val layoutKind: DisplayKind get() = kind ?: shownKind

	val shownKind: DisplayKind get() = display?.let { prefs.displays.entry(it)?.kind } ?: kind ?: prefs.kind

	@get:DrawableRes
	val symbol: Int get() = if (layoutKind == DisplayKind.Tv) R.drawable.ic_tv else R.drawable.ic_glasses

	val remoteTitle: String get() = if (layoutKind == DisplayKind.Tv) "Big Screen Remote" else "Glasses Remote"

	/** The question the phone asks on a first connection. */
	val question: String get() = display?.question ?: "Use Marquee on this display?"

	/** How the canvas lays out on the display that is connected. */
	val layout: BigScreenLayout
		get() = BigScreenLayout.of(layoutKind, display?.pixelWidth ?: 0, display?.pixelHeight ?: 0)

	/** The poster columns of a grid page: as many as fit between the margins. */
	val columns: Int
		get() = ((layout.width - 2 * layout.marginX + GlassesCanvas.TILE_GAP) / (GlassesCanvas.POSTER.first + GlassesCanvas.TILE_GAP)).toInt().coerceAtLeast(1)

	/** The remote takes the phone while Marquee's screen is up and the phone app was not picked instead; the app also waits for a chosen server. */
	val showsRemote: Boolean get() = isConnected && !remoteHidden

	/** Marquee's screen shows the player's picture, so the phone's player draws none of its own. */
	val drawsPicture: Boolean get() = isConnected

	// MARK: Settings

	fun setEnabled(enabled: Boolean) {
		update { it.copy(enabled = enabled) }
		if (!enabled) asksToUse = false
		mirroring = false
		refresh()
	}

	/** The page's "When a Display Connects". */
	fun setUse(use: BigScreenUse) {
		update { it.copy(use = use) }
		asksToUse = false
		mirroring = false
		refresh()
	}

	/** The page's "Display Type": the connected display's, remembered; otherwise what a new display is taken for. */
	fun chooseKind(kind: DisplayKind) {
		val current = display
		if (current == null) {
			update { it.copy(kind = kind) }
			return
		}
		update { it.copy(displays = it.displays.remembering(current, kind, prefs.displays.entry(current)?.use ?: BigScreenUse.Marquee)) }
		evaluate()
	}

	/** An answer from the first-connection question: Marquee's screen as glasses or a TV, or null to mirror. */
	fun answer(kind: DisplayKind?) {
		val current = display ?: run {
			asksToUse = false
			return
		}
		val chosen = kind ?: shownKind
		update { it.copy(displays = it.displays.remembering(current, chosen, if (kind == null) BigScreenUse.Mirror else BigScreenUse.Marquee)) }
		asksToUse = false
		evaluate()
	}

	fun forget(key: String) {
		update { it.copy(displays = it.displays.forgetting(key)) }
		mirroring = false
		refresh()
	}

	fun setTilt(enabled: Boolean) = update { it.copy(tilt = enabled) }

	/** Delete All Local Data: the plugin goes back to off and asks again. */
	fun reset() {
		update { BigScreenPrefs() }
		asksToUse = false
		mirroring = false
		refresh()
	}

	private fun update(change: (BigScreenPrefs) -> BigScreenPrefs) {
		prefs = change(prefs)
		val next = prefs
		scope.launch { container.settings.updateBigScreen { next } }
	}

	private fun adopt(saved: BigScreenPrefs) {
		val first = !loaded
		loaded = true
		if (saved != prefs) prefs = saved
		if (first) refresh()
	}

	// MARK: The display

	/** The phone's window is up: displays are watched while it lives. */
	fun attach(host: Activity) {
		if (activity === host) return
		activity = host
		val displays = host.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
		manager = displays
		audio = host.getSystemService(Context.AUDIO_SERVICE) as AudioManager
		displays.registerDisplayListener(listener, null)
		refresh()
	}

	fun detach(host: Activity) {
		if (activity !== host) return
		manager?.unregisterDisplayListener(listener)
		teardown()
		manager = null
		audio = null
		activity = null
		shownDisplayId = -1
		display = null
		kind = null
		asksToUse = false
	}

	/** Looks at what is connected and brings Marquee's screen up or down to match. */
	private fun refresh() {
		if (activity == null || !loaded) return
		val connected = if (!prefs.enabled) null else candidate()
		if (connected == null) {
			if (shownDisplayId != -1) sceneDisconnected()
			mirroring = false
			return
		}
		if (connected.displayId == shownDisplayId && display != null) {
			// The same display, perhaps in another mode: its answer is not asked for again.
			val identity = identityOf(connected)
			if (identity != display && !mirroring) display = identity
			evaluate()
			return
		}
		shownDisplayId = connected.displayId
		mirroring = false
		display = identityOf(connected)
		Diag.info(DiagCategory.BigScreen, "display.connected", "size" to display?.sizeLabel, "hz" to display?.refreshRate)
		evaluate()
	}

	private fun candidate(): Display? =
		manager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
			?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF && it.isValid }

	private fun identityOf(shown: Display): DisplayIdentity {
		val mode = shown.mode
		return DisplayIdentity.of(mode.physicalWidth, mode.physicalHeight, shown.refreshRate.roundToInt(), shown.name)
	}

	/**
	 * Decides what the connected display is for, from its remembered answer or the page's choice. A connected display
	 * is not taken down by Ask, which only concerns the next connection.
	 */
	private fun evaluate() {
		val current = display ?: return
		when (val outcome = DisplayOutcome.decide(current, prefs.displays, prefs.use, prefs.kind)) {
			DisplayOutcome.Ask -> if (!isConnected) asksToUse = true
			is DisplayOutcome.Marquee -> {
				asksToUse = false
				kind = outcome.kind
				mirroring = false
				if (!isConnected) connect()
			}
			DisplayOutcome.Mirror -> {
				asksToUse = false
				if (isConnected) disconnect()
				kind = null
				mirroring = true
			}
		}
	}

	private fun sceneDisconnected() {
		asksToUse = false
		teardown()
		display = null
		kind = null
		shownDisplayId = -1
	}

	private fun connect() {
		val host = activity ?: return
		val screen = manager?.getDisplay(shownDisplayId) ?: return
		val shown = try {
			GlassesPresentation(host, screen, container, this).also { it.show() }
		} catch (e: WindowManager.InvalidDisplayException) {
			Diag.warning(DiagCategory.BigScreen, "presentation.refused", "error" to e.javaClass.simpleName)
			return
		}
		presentation = shown
		isConnected = true
		remoteHidden = false
		val pages = GlassesNavigator(container, scope, this)
		navigator = pages
		pages.start()
		pending?.let { open ->
			pending = null
			attachPlayer(open.first, open.second)
		}
		// The phone locking ends the picture, so the phone stays awake while the screen is on.
		host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		watchVolume(host)
		Diag.info(DiagCategory.BigScreen, "connected", "kind" to kind?.wire)
	}

	/** What played goes on on the phone. */
	private fun disconnect() {
		presentation?.dismiss()
		presentation = null
		isConnected = false
		navigator?.close()
		navigator = null
		activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		unwatchVolume()
	}

	private fun teardown() {
		if (isConnected) disconnect()
	}

	// MARK: The remote

	/** The remote's Phone App gesture: the phone app comes back while the screen keeps running. */
	fun showPhoneApp() {
		remoteHidden = true
	}

	/** The person put the remote away: the first time, the phone app says where it comes back from. */
	fun leaveRemote() {
		showPhoneApp()
		if (prefs.leftRemote) return
		update { it.copy(leftRemote = true) }
		val words = if (layoutKind == DisplayKind.Tv) "The TV keeps its screen. The TV button" else "The glasses keep their screen. The glasses button"
		leaveHint = "$words on Home brings the remote back."
	}

	fun openSettings() {
		showPhoneApp()
		settingsOpens.tryEmit(Unit)
	}

	fun clearLeaveHint() {
		leaveHint = null
	}

	/** The big-screen button on the phone brings the remote back. */
	fun showRemote() {
		remoteHidden = false
	}

	// MARK: Playing

	fun play(ratingKey: String, titleKey: String, fromStart: Boolean) {
		requests.tryEmit(PlayRequest(ratingKey, titleKey, fromStart))
	}

	private var pending: Pair<PlayerModel, () -> Unit>? = null

	/** The phone's player opened: Marquee's screen shows its picture while it is up, and the remote drives it. */
	fun attachPlayer(model: PlayerModel, close: () -> Unit) {
		val pages = navigator
		if (pages == null) {
			pending = model to close
			return
		}
		pages.link = GlassesPlayerLink(model, close)
	}

	fun detachPlayer(model: PlayerModel) {
		if (pending?.first === model) pending = null
		val pages = navigator ?: return
		if (pages.link?.model === model) {
			pages.link = null
			pages.leftPlayer()
		}
	}

	// MARK: Volume

	private fun level(): Float {
		val manager = audio ?: return 0f
		val top = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
		return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / top
	}

	/** One step louder or quieter; true when the volume was already at that end. */
	fun stepVolume(up: Boolean): Boolean {
		val manager = audio ?: return false
		val before = level()
		val atEnd = if (up) before >= 1f else before <= 0f
		manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
		showVolume(level())
		return atEnd
	}

	/** Puts the level bar on the big screen for a moment. */
	fun showVolume(level: Float) {
		if (!isConnected) return
		volume = level
		volumeStamp++
	}

	private fun watchVolume(host: Context) {
		volume = level()
		ContextCompat.registerReceiver(host, volumeReceiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED)
	}

	private fun unwatchVolume() {
		runCatching { activity?.unregisterReceiver(volumeReceiver) }
	}

	private companion object {
		/** The system broadcasts this when a stream's volume changes; it is not in the SDK, but every release has sent it. */
		const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
		const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
	}
}

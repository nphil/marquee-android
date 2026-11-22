package com.nphil.marquee.airplay

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.cast.CastEvent
import com.nphil.marquee.cast.CastProvider
import com.nphil.marquee.cast.CastReceiver
import com.nphil.marquee.cast.CastRouteId
import com.nphil.marquee.cast.CastTransport
import com.nphil.marquee.core.CastPlan
import com.nphil.marquee.core.CastStatus
import com.nphil.marquee.data.AppSettings
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Reason
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.RemoteCommand
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.ReceiverDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Immutable
internal data class AirPlayPairing(val password: Boolean, val rejected: Boolean)

/** AirPlay 2 video only. Discovery and player ownership are independent of Google's sender. Main thread. */
@Stable
internal class AirPlaySender(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) : CastTransport {
	private val app = context.applicationContext
	private val discovery by lazy { ReceiverDiscovery(app) }
	private val client by lazy {
		Airkast(app) {
			// The library's defaults describe an iPhone. The receiver must approve Marquee on its own merits.
			identity = SenderIdentity(
				name = identity.name,
				deviceId = identity.deviceId,
				model = "Marquee",
				osName = "Android",
				osVersion = Build.VERSION.RELEASE,
				osBuildVersion = BuildConfig.VERSION_NAME,
				sourceVersion = "0.6.0",
				userAgent = "AirPlay/550.10 Marquee/${BuildConfig.VERSION_NAME}",
			)
		}
	}
	private var searches = 0
	private var scan: Job? = null
	private var joining: Job? = null
	private var typed: CompletableDeferred<String>? = null
	private var sink: ((CastEvent) -> Unit)? = null
	private var active: Connection? = null
	private var serial = 0

	override var enabled by mutableStateOf(false)
		private set
	override var devices: List<CastReceiver> by mutableStateOf(emptyList())
		private set
	override var needsPermission by mutableStateOf(false)
		private set
	override var discoveryProblem: String? by mutableStateOf(null)
		private set
	override var connectionProblem: String? by mutableStateOf(null)
		private set
	var pairing: AirPlayPairing? by mutableStateOf(null)
		private set
	override var connecting: CastReceiver? by mutableStateOf(null)
		private set
	override var connected: CastReceiver? by mutableStateOf(null)
		private set

	override val mediaSessionId: Int? get() = active?.load?.id
	override val positionMs: Long? get() = active?.load?.takeIf { it.ready }?.positionMs()

	init {
		scope.launch {
			settings.airPlayEnabled.collect { on ->
				enabled = on
				if (!on) disconnect()
				refreshDiscovery()
			}
		}
	}

	override fun setOptIn(on: Boolean) {
		scope.launch { settings.setAirPlayEnabled(on) }
	}

	override fun beginSearch() {
		if (searches++ == 0) discoveryProblem = null
		refreshDiscovery()
	}

	override fun endSearch() {
		if (searches > 0) searches--
		refreshDiscovery()
	}

	override fun refreshDiscovery() {
		needsPermission = enabled && Build.VERSION.SDK_INT >= 37 &&
			ContextCompat.checkSelfPermission(app, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
		if (!enabled || searches == 0 || needsPermission) {
			scan?.cancel()
			scan = null
			devices = emptyList()
			return
		}
		if (scan?.isActive == true) return
		discoveryProblem = null
		scan = scope.launch {
			discovery.receivers.catch { failure ->
				devices = emptyList()
				needsPermission = Build.VERSION.SDK_INT >= 37 && failure is AirkastException.NotPermitted
				if (!needsPermission) discoveryProblem = "AirPlay could not look for devices on this network."
			}.collect { receivers ->
				devices = receivers.filter(::acceptsAirPlayVideo).distinctBy { it.id }
					.map { CastReceiver(CastRouteId(CastProvider.AirPlay, it.id), it.name, it.model) }
					.sortedBy { it.name }
			}
		}
	}

	override fun connect(receiver: CastReceiver, events: (CastEvent) -> Unit, beforeConnect: () -> Unit) {
		if (sink != null && sink !== events) return
		if (!enabled || needsPermission || connecting != null || connected?.id == receiver.id) return
		if (receiver.id.provider != CastProvider.AirPlay) return
		val target = discovery.find(receiver.id.nativeId)?.takeIf { devices.any { it.id == receiver.id } && acceptsAirPlayVideo(it) }
		if (target == null) {
			connectionProblem = "That AirPlay receiver is no longer available."
			return
		}
		beforeConnect()
		if (active != null) {
			val at = positionMs
			val previous = sink
			leave(stopping = true, report = false)
			previous?.invoke(CastEvent.Switching(at))
		}
		connectionProblem = null
		connecting = receiver
		sink = events
		joining = scope.launch {
			var rejected = false
			while (isActive) {
				try {
					val session = client.connect(target) { secret ->
						val answer = CompletableDeferred<String>()
						typed = answer
						pairing = AirPlayPairing(secret == Secret.Password, rejected)
						// The UI owns the attempt; closing it cancels this wait without storing the answer.
						try {
							answer.await()
						} finally {
							if (typed === answer) {
								typed = null
								pairing = null
							}
						}
					}
					val connection = Connection(session)
					joining = null
					active = connection
					connection.events = scope.launch { session.events.collect { receive(connection, it) } }
					events(CastEvent.Connected(receiver))
					return@launch
				} catch (_: AirkastException.SecretRejected) {
					rejected = true // The receiver issues a new PIN. Never reuse the failed one.
				} catch (error: AirkastException) {
					connectionProblem = when (error) {
						is AirkastException.NotPermitted -> {
							refreshDiscovery()
							"Allow Marquee to find devices on the local network."
						}
						is AirkastException.PairingFailed -> "The receiver refused AirPlay pairing. Check its AirPlay access settings."
						is AirkastException.Timeout -> "The AirPlay receiver did not answer. Check for an approval prompt on its screen."
						else -> "The AirPlay receiver could not connect."
					}
					leave(stopping = false)
					return@launch
				}
			}
		}
	}

	fun enterSecret(value: String) {
		val prompt = pairing ?: return
		if (validAirPlaySecret(value, prompt.password)) typed?.complete(value)
	}

	override fun activate(): Boolean {
		val load = active?.load ?: return false
		if (!load.ready || load.ended) return false
		connecting?.let {
			connected = it
			connecting = null
		}
		return true
	}

	override fun rejectHandoff(message: String) {
		connectionProblem = message
		leave(stopping = true)
	}

	override fun cancelPending(events: (CastEvent) -> Unit) {
		if (sink === events && connecting != null) disconnect()
	}

	override fun disconnect(stopping: Boolean) {
		connectionProblem = null
		leave(stopping)
	}

	override fun playerClosed(events: (CastEvent) -> Unit) {
		if (sink === events) leave(stopping = true, report = false)
	}

	private fun leave(stopping: Boolean, report: Boolean = true) {
		val at = positionMs
		val connection = active
		val events = sink
		sink = null
		joining?.cancel()
		joining = null
		typed = null
		pairing = null
		connecting = null
		connected = null
		active = null
		connection?.let {
			it.events?.cancel()
			it.poll?.cancel()
			if (stopping && it.mediaOwned) {
				scope.launch {
					try {
						it.commands.withLock { it.session.stop() }
					} catch (_: AirkastException) {
						// It may have left the network while the session was closing.
					} finally {
						it.session.close()
					}
				}
			} else {
				it.session.close()
			}
		}
		if (report) events?.invoke(CastEvent.Disconnected(at))
	}

	override fun load(plan: CastPlan, autoplay: Boolean): Boolean {
		val connection = active ?: return false
		val file = airPlayFileUrl(plan.plan.stream, plan.contentType) ?: return false
		val at = (plan.plan.startAt.coerceAtLeast(0.0) * 1000).toLong()
		val load = Load(++serial, at, connection.load?.item)
		connection.poll?.cancel()
		connection.load = load
		connection.poll = scope.launch {
			try {
				connection.commands.withLock {
					connection.mediaOwned = true
					connection.session.load(Media(file, at.milliseconds, MediaFormat.File))
					// The library starts video when loading it. Wait for the real receiver state before a handoff.
					if (!autoplay) connection.session.pause()
				}
				val queuedAt = SystemClock.elapsedRealtime()
				while (isActive && !load.ended) {
					val info = try {
						connection.session.playbackInfo()
					} catch (_: AirkastException.Timeout) {
						null
					}
					if (!owns(connection, load)) return@launch
					if (info != null) receivedStatus(connection, load, info)
					if (!load.ready && SystemClock.elapsedRealtime() - queuedAt >= 15_000) finish(load, "error")
					if (!load.ended) delay(1_000)
				}
			} catch (error: AirkastException) {
				if (owns(connection, load) && error !is AirkastException.Disconnected) finish(load, "error")
			}
		}
		return true
	}

	private fun receivedStatus(connection: Connection, load: Load, info: PlaybackInfo) {
		val item = info.itemId
		if (item != null && load.item == null && item == load.previousItem) return
		if (load.item == null) load.item = item
		if (item != null && load.item != item) {
			// Do not send Stop for a load that no longer belongs to this cast.
			connection.mediaOwned = false
			finish(load, "interrupted")
			return
		}
		when (val state = airPlayState(info.state, info.rate, load.ready)) {
			null -> Unit
			"cancelled" -> finish(load, state)
			else -> {
				val at = info.position?.inWholeMilliseconds
				// A queue acknowledgment or an empty playback clock does not show that the file opened.
				if (!load.ready && (state == "playing" || state == "paused")) {
					if (at == null || (info.duration?.inWholeMilliseconds ?: 0) <= 0) return
					load.ready = true
				}
				publish(load, state, null, at, info.rate)
			}
		}
	}

	override fun setPlaying(playing: Boolean) = control { session, _ -> if (playing) session.play() else session.pause() }

	override fun seek(ms: Long): Boolean = control { session, load ->
		val at = session.seek(ms.coerceAtLeast(0).milliseconds) ?: return@control
		val previous = load.status ?: return@control
		if (!load.ended) publish(load, previous.player, null, at.inWholeMilliseconds, previous.rate)
	}

	override suspend fun positionBeforeHandoff(ms: Long): Boolean {
		val connection = active ?: return false
		val load = connection.load?.takeIf { it.ready && !it.ended } ?: return false
		if (abs(load.positionMs() - ms) < 500) return true
		return withTimeoutOrNull(8_000) {
			connection.commands.withLock {
				if (!owns(connection, load)) return@withLock false
				val at = connection.session.seek(ms.milliseconds)?.inWholeMilliseconds ?: return@withLock false
				if (!owns(connection, load)) return@withLock false
				publish(load, "paused", null, at, 0.0)
				true
			}
		} ?: false
	}

	override fun stopMedia() {
		val connection = active ?: return
		if (connection.load == null) return
		connection.load = null
		connection.poll?.cancel()
		scope.launch {
			try {
				connection.commands.withLock {
					if (active === connection && connection.load == null && connection.mediaOwned) {
						connection.session.stop()
						connection.mediaOwned = false
					}
				}
			} catch (_: AirkastException) {
				// There is no longer any owned playback to report an error against.
			}
		}
	}

	private fun control(command: suspend (AirkastSession, Load) -> Unit): Boolean {
		val connection = active ?: return false
		val load = connection.load?.takeIf { it.ready && !it.ended } ?: return false
		scope.launch {
			try {
				connection.commands.withLock { if (owns(connection, load)) command(connection.session, load) }
			} catch (error: AirkastException) {
				if (owns(connection, load) && error !is AirkastException.Disconnected) {
					connectionProblem = "The AirPlay receiver did not accept the playback control."
				}
			}
		}
		return true
	}

	private fun owns(connection: Connection, load: Load) = active === connection && connection.load === load && !load.ended

	private fun receive(connection: Connection, event: ReceiverEvent) {
		if (active !== connection) return
		val load = connection.load?.takeUnless { it.ended }
		when (event) {
			is ReceiverEvent.Disconnected -> {
				if (event.reason == DisconnectReason.ConnectionFailed) connectionProblem = "The AirPlay connection was lost."
				leave(stopping = false)
			}
			ReceiverEvent.Back -> leave(stopping = true)
			is ReceiverEvent.ItemEnded -> if (load != null && load.item != null && load.item == event.itemId) {
				finish(load, "finished")
			}
			is ReceiverEvent.StateChanged -> if (load?.ready == true && event.state == PlaybackState.Stopped) {
				when (event.reason) {
					Reason.Ended -> finish(load, "finished")
					Reason.Interrupted -> leave(stopping = false)
				}
			}
			is ReceiverEvent.RemoteCommand -> when (event.command) {
				RemoteCommand.Play -> setPlaying(true)
				RemoteCommand.Pause -> setPlaying(false)
				RemoteCommand.PlayPause -> setPlaying(load?.status?.player != "playing")
				else -> Unit
			}
			else -> Unit
		}
	}

	private fun finish(load: Load, reason: String) = publish(load, "idle", reason, null, 0.0)

	private fun publish(load: Load, state: String, idle: String?, ms: Long?, rate: Double) {
		if (active?.load !== load || load.ended) return
		val at = ms ?: load.positionMs()
		val status = CastStatus(load.id, state, idle, at / 1_000.0, rate.takeIf { state == "playing" && it.isFinite() && it > 0 } ?: 0.0)
		if (load.status == status) return
		load.status = status
		load.readAt = SystemClock.elapsedRealtime()
		load.ended = idle != null
		sink?.invoke(CastEvent.Progress(status))
	}

	private class Connection(val session: AirkastSession) {
		val commands = Mutex()
		var events: Job? = null
		var poll: Job? = null
		var mediaOwned = false
		var load: Load? = null
	}

	private class Load(val id: Int, val startMs: Long, val previousItem: String?) {
		var item: String? = null
		var ready = false
		var ended = false
		var status: CastStatus? = null
		var readAt = 0L

		fun positionMs(): Long {
			val reading = status ?: return startMs
			return (reading.position * 1_000 + (SystemClock.elapsedRealtime() - readAt) * reading.rate).toLong()
		}
	}
}

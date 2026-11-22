package com.nphil.marquee.cast

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nphil.marquee.airplay.AirPlaySender
import com.nphil.marquee.core.CastPlan
import com.nphil.marquee.core.CastStatus
import com.nphil.marquee.data.AppSettings
import kotlinx.coroutines.CoroutineScope

internal enum class CastProvider(val label: String) { Chromecast("Chromecast"), AirPlay("AirPlay") }

@Immutable
internal data class CastRouteId(val provider: CastProvider, val nativeId: String)

@Immutable
internal data class CastReceiver(val id: CastRouteId, val name: String, val model: String?)

internal sealed interface CastEvent {
	data class Connected(val receiver: CastReceiver) : CastEvent
	data class Switching(val positionMs: Long?) : CastEvent
	data class Disconnected(val positionMs: Long?) : CastEvent
	data class Progress(val status: CastStatus) : CastEvent
}

@Stable
internal interface CastTransport {
	val enabled: Boolean
	val devices: List<CastReceiver>
	val needsPermission: Boolean
	val discoveryProblem: String?
	val connectionProblem: String?
	val connecting: CastReceiver?
	val connected: CastReceiver?
	val mediaSessionId: Int?
	val positionMs: Long?

	fun setOptIn(on: Boolean)
	fun beginSearch()
	fun endSearch()
	fun refreshDiscovery()
	fun connect(receiver: CastReceiver, events: (CastEvent) -> Unit, beforeConnect: () -> Unit)
	fun activate(): Boolean
	fun rejectHandoff(message: String)
	fun cancelPending(events: (CastEvent) -> Unit)
	fun disconnect(stopping: Boolean = true)
	fun playerClosed(events: (CastEvent) -> Unit)
	fun load(plan: CastPlan, autoplay: Boolean): Boolean
	fun setPlaying(playing: Boolean): Boolean
	fun seek(ms: Long): Boolean
	suspend fun positionBeforeHandoff(ms: Long): Boolean
	fun stopMedia()
}

/** One playback output and one route list, with discovery and credentials kept in the respective senders. */
@Stable
internal class Casting(context: Context, settings: AppSettings, scope: CoroutineScope) {
	private val chromecast = CastSender(context, settings, scope)
	private val airPlay = AirPlaySender(context, settings, scope)
	val transports: List<CastTransport> = listOf(chromecast, airPlay)
	private var lastAttempt by mutableStateOf<CastTransport?>(null)

	operator fun get(provider: CastProvider): CastTransport = when (provider) {
		CastProvider.Chromecast -> chromecast
		CastProvider.AirPlay -> airPlay
	}

	val enabled get() = transports.any { it.enabled }
	val connecting get() = transports.firstNotNullOfOrNull { it.connecting }
	val connected get() = transports.firstNotNullOfOrNull { it.connected }
	val transport get() = (connecting ?: connected)?.let { get(it.id.provider) }
	val pairing get() = airPlay.pairing
	val connectionProblem get() = lastAttempt?.connectionProblem

	fun refreshDiscovery() = transports.forEach { it.refreshDiscovery() }

	fun connect(receiver: CastReceiver, events: (CastEvent) -> Unit) {
		if (connecting != null || connected?.id == receiver.id) return
		val current = transport
		val target = get(receiver.id.provider)
		lastAttempt = target
		target.connect(receiver, events) {
			if (current != null && current !== target) {
				val at = current.positionMs
				current.playerClosed(events)
				events(CastEvent.Switching(at))
			}
		}
	}

	fun enterSecret(value: String) = airPlay.enterSecret(value)
	fun adjustVolume(by: Double) = chromecast.adjustVolume(by)
	fun toggleMute() = chromecast.toggleMute()
}

/** Names are display text: one receiver may advertise both protocols or share a name with another TV. */
internal fun castChoices(devices: List<CastReceiver>, pending: CastReceiver?, current: CastReceiver?): List<CastReceiver> =
	(devices + listOfNotNull(pending, current)).distinctBy { it.id }

/** A connection, a queued load and buffering are not proof the receiver can play the file. */
internal fun CastStatus.resolvesHandoff(id: Int, expectedState: String): Boolean =
	mediaSessionId == id && (idle != null || player == expectedState)

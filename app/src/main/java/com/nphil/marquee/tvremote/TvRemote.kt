package com.nphil.marquee.tvremote

import android.content.Context
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.core.content.edit
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.core.NativeCore
import com.nphil.marquee.data.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Where the remote opens from. */
internal enum class RemoteAccess(val id: String, val title: String) {
	ProfileButton("profile", "Button by Profile"),
	MiniBar("miniBar", "Mini Bar"),
	;

	companion object {
		/** The Mini Bar is the only choice kept by name; a saved floating button, or no choice, is the profile button. */
		fun decoded(id: String?) = if (id == MiniBar.id) MiniBar else ProfileButton
	}
}

@Serializable
internal data class SavedTv(
	val id: String, val name: String, val mac: String, val device: String, val key: String,
	val host: String, val port: Int, val service: String? = null, val pairedAt: Double,
)

@Serializable
internal data class TvStatus(
	val tv: SavedTv, val status: String, val standby: Boolean = false, val keyboard: Boolean = false,
	val privateText: Boolean = false, val keyboardPurpose: Int? = null, val enter: Int? = null,
	val volume: Double? = null, val volumeMax: Double? = null, val muted: Boolean? = null,
	val actions: Set<String> = emptySet(), val pointer: Boolean = false, val notice: String? = null,
	val noticeID: Int = 0,
) {
	val ready: Boolean get() = status == "connected" && !standby
}

@Serializable
internal data class TvInvite(val host: String, val port: Int, val id: String, val code: String)

@Serializable
internal data class TvListItem(val id: JsonPrimitive, val label: String, val number: String? = null)

@Serializable
internal data class RemoteRequest(
	val session: String? = null, val tv: SavedTv? = null, val url: String? = null,
	val id: String? = null, val code: String? = null, val host: String? = null, val port: Int? = null, val service: String? = null,
	val name: String? = null, val model: String? = null, val app: String? = null,
	val key: String? = null, val press: String? = null, val text: String? = null, val edit: String? = null, val count: Int? = null,
	val action: String? = null, val item: JsonPrimitive? = null, val level: Double? = null, val kind: String? = null,
	val pointer: String? = null, val x: Double? = null, val y: Double? = null,
)

@Serializable
private class RemoteAck

internal class RemoteRequestFailed(message: String) : Exception(message)

internal object TvCore {
	val json = Json { ignoreUnknownKeys = true }

	suspend inline fun <reified T> call(operation: String, request: RemoteRequest): T = withContext(Dispatchers.IO) {
		val reply = NativeCore.call("remote.$operation", json.encodeToString(request).encodeToByteArray())
		val body = json.parseToJsonElement(reply.decodeToString()).jsonObject
		val ok = body["ok"]
		if (ok == null) throw RemoteRequestFailed(body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
			?: "The TV remote couldn’t complete that action.")
		json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), ok)
	}
}

/** The remote's own settings and pairing keys are local to this device, not tied to a Plex profile. */
internal class TvRemote(private val context: Context, private val scope: CoroutineScope) {
	data class State(
		val enabled: Boolean, val opened: Boolean = false, val paired: List<SavedTv> = emptyList(), val selected: String? = null,
		val status: TvStatus? = null, val discovery: TvDiscovery = TvDiscovery(), val invite: TvInvite? = null,
		val busy: Boolean = false, val notice: String? = null, val phoneName: String = Build.MODEL,
		val loaded: Boolean = false, val access: RemoteAccess = RemoteAccess.ProfileButton, val origin: Rect? = null,
	) {
		val current: SavedTv? get() = paired.firstOrNull { it.id == selected }
	}

	private val settings = context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)
	private val secrets = SecretStore(context, "tv_remote_pairing")
	private val storage = Mutex()
	private val mutable = MutableStateFlow(State(enabled = settings.getBoolean("enabled", false),
		access = RemoteAccess.decoded(settings.getString("access", null)),
		phoneName = settings.getString("phoneName", null) ?: Build.MODEL))
	val state = mutable.asStateFlow()
	private var available = false
	private var discovery: Job? = null
	private var connection: Job? = null
	private var endpoint: Triple<String, String, Int>? = null
	private var active: Session? = null
	private var action: Job? = null

	private class Session(val id: String, val commands: Channel<RemoteRequest> = Channel(64))

	fun enable(enabled: Boolean) {
		settings.edit { putBoolean("enabled", enabled) }
		mutable.update { it.copy(enabled = enabled, opened = enabled && it.opened, invite = if (enabled) it.invite else null) }
		if (!enabled) networkAvailable(false)
	}

	/** Opens the panel, growing out of [origin] (the button's bounds in window px) when a button opened it. */
	fun open(origin: Rect? = null) {
		if (mutable.value.enabled) mutable.update { it.copy(opened = true, origin = origin, notice = null) }
	}

	fun setAccess(access: RemoteAccess) {
		settings.edit { putString("access", access.id) }
		mutable.update { it.copy(access = access) }
	}

	fun close() {
		if (mutable.value.busy) return
		mutable.update { it.copy(opened = false, origin = null, invite = null, notice = null) }
		networkAvailable(false)
	}

	fun openInvite(url: String) {
		scope.launch {
			if (mutable.value.busy) return@launch
			try {
				val invite = TvCore.call<TvInvite>("invite", RemoteRequest(url = url))
				enable(true)
				mutable.update { it.copy(opened = true, origin = null, invite = invite, notice = null) }
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { showError(e) }
		}
	}

	fun dismissInvite() { if (!mutable.value.busy) mutable.update { it.copy(invite = null, notice = null) } }

	fun networkAvailable(available: Boolean) {
		if (this.available == available) return
		this.available = available
		discovery?.cancel()
		if (available) {
			discovery = scope.launch {
				loadPairings()
				connectSelected()
				discoverTvs(context).collect { result ->
					mutable.update { it.copy(discovery = result) }
					connectSelected()
				}
			}
		} else {
			discovery = null
			mutable.update { it.copy(discovery = TvDiscovery(), status = null) }
			connectSelected()
		}
	}

	fun pair(invite: TvInvite, name: String, onPaired: () -> Unit) = pair(RemoteRequest(
		id = invite.id, code = invite.code, host = invite.host, port = invite.port,
	), name, onPaired)

	fun pair(tv: TvAdvertisement, code: String, name: String, onPaired: () -> Unit) = pair(RemoteRequest(
		id = tv.id, code = code, host = tv.host, port = tv.port, service = tv.service,
	), name, onPaired)

	private fun pair(request: RemoteRequest, name: String, onPaired: () -> Unit) {
		if (!available || !mutable.value.loaded || mutable.value.busy) return
		mutable.update { it.copy(busy = true, notice = null) }
		scope.launch {
			try {
				val tv = TvCore.call<SavedTv>("pair", request.phone(name.trim()))
				storage.withLock {
					val paired = mutable.value.paired.filterNot { it.id == tv.id } + tv
					save(paired)
					settings.edit { putString("phoneName", name.trim()); putString("selected", tv.id) }
					mutable.update { it.copy(paired = paired, selected = tv.id, phoneName = name.trim(), invite = null) }
				}
				connectSelected(restart = true)
				onPaired()
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { showError(e) }
			finally { mutable.update { it.copy(busy = false) } }
		}
	}

	fun select(tv: SavedTv) {
		if (mutable.value.selected == tv.id) return
		settings.edit { putString("selected", tv.id) }
		mutable.update { it.copy(selected = tv.id, notice = null) }
		connectSelected()
	}

	fun forget(tv: SavedTv) {
		scope.launch {
			try {
				storage.withLock {
					val paired = mutable.value.paired.filterNot { it.id == tv.id }
					save(paired)
					val selected = if (mutable.value.selected == tv.id) paired.lastOrNull()?.id else mutable.value.selected
					settings.edit { putString("selected", selected) }
					mutable.update { it.copy(paired = paired, selected = selected, notice = null) }
				}
				connectSelected()
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { showError(e) }
		}
	}

	fun power() {
		val now = mutable.value
		if (now.status?.status == "connected") {
			input(RemoteRequest(key = "power"))
		} else if (available && now.current != null) {
			scope.launch {
				try { TvCore.call<RemoteAck>("wake", RemoteRequest(tv = now.current)) }
				catch (e: CancellationException) { throw e }
				catch (e: Exception) { showError(e) }
				if (mutable.value.selected == now.selected) connectSelected(restart = true)
			}
		}
	}

	fun reconnect() {
		clearNotice()
		connectSelected(restart = true)
	}

	fun input(request: RemoteRequest) {
		val session = active ?: return
		if (session.commands.trySend(request).isSuccess) return
		// Releasing the connection also releases held keys if its command queue has stalled.
		if (request.press == "up" || request.pointer == "end" || request.pointer == "up") reconnect()
		else if (request.pointer == null) report("The TV remote is busy.")
	}

	fun perform(request: RemoteRequest, done: () -> Unit = {}) {
		if (action?.isActive == true) return
		val session = active ?: return
		action = scope.launch {
			try {
				TvCore.call<RemoteAck>("action", request.copy(session = session.id))
				if (active === session) done()
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { if (active === session) showError(e) }
		}
	}

	suspend fun list(kind: String): List<TvListItem> = TvCore.call("list", RemoteRequest(session = active?.id, kind = kind))

	fun clearNotice() { mutable.update { it.copy(notice = null) } }
	fun report(message: String) { mutable.update { it.copy(notice = message) } }

	private fun connectSelected(restart: Boolean = false) {
		val tv = mutable.value.current?.takeIf { available }
		val seen = mutable.value.discovery.televisions.firstOrNull { it.id == tv?.id }
		// Only a newly selected or disconnected TV needs the discovered address; don't interrupt a live session.
		val reachable = if (seen != null && tv != null && (mutable.value.status?.status != "connected" || restart))
			tv.copy(host = seen.host, port = seen.port, service = seen.service) else tv
		val next = reachable?.let { Triple(it.id, it.host, it.port) }
		if (next == endpoint && !restart) return
		endpoint = next
		val before = connection
		before?.cancel()
		action?.cancel()
		active = null
		mutable.update { it.copy(status = reachable?.let { tv -> TvStatus(tv, "connecting") }) }
		connection = scope.launch {
			before?.join()
			if (reachable == null) return@launch
			val session = Session(UUID.randomUUID().toString())
			val request = RemoteRequest(session = session.id, tv = reachable).phone(mutable.value.phoneName)
			try {
				withContext(NonCancellable) { TvCore.call<RemoteAck>("open", request) }
				currentCoroutineContext().ensureActive()
				active = session
				coroutineScope {
					val sender = launch {
						for (command in session.commands) {
							try { TvCore.call<RemoteAck>(if (command.pointer == null) "input" else "pointer", command.copy(session = session.id)) }
							catch (e: CancellationException) { throw e }
							catch (e: Exception) { if (active === session && command.pointer == null) showError(e) }
						}
					}
					var noticeID = 0
					while (true) {
						val status = TvCore.call<TvStatus>("event", RemoteRequest(session = session.id))
						if (active !== session) break
						if (status.status == "connected") remember(status.tv)
						if (active !== session) break
						mutable.update { it.copy(status = status, notice = if (status.noticeID > noticeID) status.notice else it.notice) }
						noticeID = status.noticeID
						if (status.status in listOf("unpaired", "incompatible")) break
						// TVLink keeps retrying. Switch to its advertised address once the old address stops working.
						if (status.status == "offline") connectSelected()
					}
					sender.cancelAndJoin()
				}
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) {
				if (active === session || endpoint == next) {
					mutable.update { it.copy(status = TvStatus(reachable, "offline")) }
					showError(e)
				}
			} finally {
				if (active === session) active = null
				session.commands.close()
				withContext(NonCancellable) { runCatching { TvCore.call<RemoteAck>("close", RemoteRequest(session = session.id)) } }
			}
		}
	}

	private suspend fun loadPairings() = storage.withLock {
		if (mutable.value.loaded) return@withLock
		val paired = withContext(Dispatchers.IO) {
			secrets.read("pairings")?.let { runCatching { TvCore.json.decodeFromString<List<SavedTv>>(it) }.getOrNull() }.orEmpty()
		}
		val selected = settings.getString("selected", null)
		mutable.update { it.copy(loaded = true, paired = paired, selected = paired.firstOrNull { tv -> tv.id == selected }?.id ?: paired.lastOrNull()?.id) }
	}

	private suspend fun remember(tv: SavedTv) {
		try {
			// This write and its matching in-memory update must finish together if the remote closes.
			withContext(NonCancellable) {
				storage.withLock {
					val old = mutable.value.paired
					val saved = old.map { if (it.id == tv.id && it.pairedAt == tv.pairedAt) tv else it }
					if (saved != old) {
						save(saved)
						mutable.update { it.copy(paired = saved) }
					}
				}
			}
		} catch (e: CancellationException) { throw e }
		catch (e: Exception) { showError(e) }
	}

	private suspend fun save(pairings: List<SavedTv>) = withContext(Dispatchers.IO) {
		secrets.write("pairings", TvCore.json.encodeToString(pairings))
	}

	private fun showError(e: Exception) = report(if (e is RemoteRequestFailed) e.message ?: "TV remote error." else "Couldn’t complete that TV remote request.")
	private fun RemoteRequest.phone(name: String) = copy(name = name, model = Build.MODEL, app = "Marquee ${BuildConfig.VERSION_NAME}")
}

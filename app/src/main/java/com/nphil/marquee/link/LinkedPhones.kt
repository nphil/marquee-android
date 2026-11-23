package com.nphil.marquee.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.nphil.marquee.data.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom

/** A linked phone as kept on this device; `key` is the base64 key both phones derived when they linked. */
@Serializable
internal data class LinkedPhone(val id: String, val name: String, val key: String, val linkedAt: Double)

@Serializable
private data class Vault(val id: String, val phones: List<LinkedPhone>)

/**
 * Linked phones (DESIGN §8.10): two phones with Marquee, linked once by a code one shows and the other scans, find each
 * other on the same network from then on, and either one can be the remote for what the other plays. Off until
 * Settings › Linked Phones turns it on, and on the network only while Marquee is in front with a phone to find, a code
 * on show or a code being read. The Core's bridge owns the protocol; this keeps the saved phones (sealed in the
 * Keystore, local to the device and apart from the Plex account), mDNS, and the screens' state. Main thread only.
 */
internal class LinkedPhones(
	private val context: Context,
	private val scope: CoroutineScope,
	/** The signed-in profile's name, for the default name of this phone. */
	private val profileName: () -> String?,
) {
	/** A connected linked phone, and what it last said it shows. */
	data class Presence(val status: LinkStatus? = null, val statusAt: Long = SystemClock.elapsedRealtime())

	/** How showing a code or reading one ended. */
	sealed interface Outcome {
		data class Linked(val name: String) : Outcome
		data class Failed(val message: String) : Outcome
	}

	data class State(
		val enabled: Boolean,
		val customName: String,
		val name: String,
		val phones: List<LinkedPhone> = emptyList(),
		val presence: Map<String, Presence> = emptyMap(),
		/** The code Show Link Code shows, while it shows, and how its pairing went. */
		val invite: LinkShown? = null,
		val inviteOutcome: Outcome.Linked? = null,
		/** A code was read and this phone is linking with the one that showed it. */
		val linking: Boolean = false,
		/** The phone this one is the remote for, while that remote shows. */
		val controlling: String? = null,
		/** Marquee may not use the local network, or mDNS cannot run, so no phone can be found. */
		val unavailable: Boolean = false,
		val notice: String? = null,
	) {
		fun phone(id: String) = phones.firstOrNull { it.id == id }

		/** The first connected phone driving a screen or playing: the one Home offers to control. */
		val controllable: LinkedPhone? get() = if (!enabled) null else phones.firstOrNull { presence[it.id]?.status?.isControllable == true }
	}

	private val settings = context.getSharedPreferences("link", Context.MODE_PRIVATE)
	private val secrets = SecretStore(context, "link")
	private val storage = Mutex()
	private val mutable = MutableStateFlow(
		State(enabled = settings.getBoolean("enabled", false), customName = settings.getString("name", null).orEmpty(), name = ""),
	)
	val state = mutable.asStateFlow()
	val host = LinkHost(context)

	/** Linked phones mDNS shows, and whether the listener answers: what reading a code waits for. */
	private val seen = MutableStateFlow(LinkDiscovery())
	private val ready = MutableStateFlow(false)
	private val outbox = Channel<Pair<String, LinkWire>>(64)
	private var id: String? = null
	private var foreground = false
	private var inviting = false
	private var scanning = false
	private var run: Job? = null
	private var restart: Job? = null

	init {
		mutable.update { it.copy(name = displayName(it)) }
		scope.launch { for ((peer, message) in outbox) runCatching { LinkCore.send(peer, message) } }
	}

	// MARK: Settings

	fun setEnabled(enabled: Boolean) {
		settings.edit { putBoolean("enabled", enabled) }
		mutable.update { it.copy(enabled = enabled) }
		if (!enabled) {
			inviting = false
			stopControlling()
			mutable.update { it.copy(invite = null, inviteOutcome = null, notice = null) }
		}
		evaluate()
	}

	/** A new name, which connected phones hear at once. */
	fun setName(name: String) {
		val cleaned = cleanName(name)
		if (cleaned == mutable.value.customName) return
		settings.edit { putString("name", cleaned) }
		mutable.update { val next = it.copy(customName = cleaned); next.copy(name = displayName(next)) }
		if (ready.value) scope.launch { runCatching { LinkCore.call<LinkAck>("name", LinkRequest(name = mutable.value.name)) } }
	}

	/** Marquee came to the front or left it: a background app's mDNS and listener are not kept up. */
	fun setForeground(foreground: Boolean) {
		this.foreground = foreground
		mutable.update { it.copy(name = displayName(it)) }
		evaluate()
	}

	fun clearNotice() { mutable.update { it.copy(notice = null) } }

	/** Android 17 asks for local network access at run time; before that the permission is the manifest's. */
	fun hasNetworkAccess() = Build.VERSION.SDK_INT < 37 ||
		ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

	/** The user answered the local network prompt. */
	fun networkAccessChanged() = evaluate()

	/** Unlinks a phone on both sides: a connected one hears it now; one out of reach keeps listing this phone until it is unlinked there too. */
	fun unlink(peer: String) {
		scope.launch {
			runCatching { LinkCore.call<LinkAck>("forget", LinkRequest(peer = peer, unlink = true)) }
			forget(peer)
		}
	}

	// MARK: Pairing

	/** Opens the code Show Link Code shows. It lasts while it shows and links one phone. */
	fun openInvite() {
		if (!mutable.value.enabled) return
		inviting = true
		mutable.update { it.copy(invite = null, inviteOutcome = null, notice = null) }
		evaluate()
		scope.launch {
			if (!awaitReady()) return@launch noNetwork()
			try {
				val shown = LinkCore.call<LinkShown>("invite.open")
				if (inviting) mutable.update { it.copy(invite = shown) }
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { report(e) }
		}
	}

	fun closeInvite() {
		inviting = false
		mutable.update { it.copy(invite = null, inviteOutcome = null) }
		scope.launch { runCatching { LinkCore.call<LinkAck>("invite.close") } }
		evaluate()
	}

	/**
	 * Links with the phone whose code [url] was scanned: this phone finds it on the network, calls it, both prove they
	 * hold the code's secret, and each adds a share to the key they keep.
	 */
	suspend fun join(url: String): Outcome {
		if (!mutable.value.enabled || mutable.value.linking) return Outcome.Failed("Turn on Linked Phones first.")
		if (!hasNetworkAccess()) return Outcome.Failed("Marquee can’t use the local network, so it can’t find the other phone.")
		scanning = true
		mutable.update { it.copy(linking = true, notice = null) }
		evaluate()
		try {
			val code = LinkCore.call<LinkCode>("parse", LinkRequest(url = url))
			if (!awaitReady()) return Outcome.Failed("Marquee couldn’t start looking for linked phones. Try again.")
			val phone = withTimeoutOrNull(FIND_MS) { seen.first { found -> found.phones.any { it.id == code.id } } }
				?.phones?.firstOrNull { it.id == code.id }
				?: return Outcome.Failed(unreachable(code.name))
			val linked = LinkCore.call<LinkedPeer>("join", LinkRequest(url = url, host = phone.host, port = phone.port))
			add(linked)
			return Outcome.Linked(mutable.value.phone(linked.id)?.name ?: linked.name)
		} catch (e: CancellationException) { throw e }
		catch (e: LinkFailed) { return Outcome.Failed(e.message ?: "Not linked.") }
		catch (e: Exception) { return Outcome.Failed("The linked phone couldn’t complete that action.") }
		finally {
			scanning = false
			mutable.update { it.copy(linking = false) }
			evaluate()
		}
	}

	// MARK: Controlling

	/** Shows this phone's remote for a linked phone. */
	fun control(peer: String) {
		if (mutable.value.controlling == peer || mutable.value.phone(peer) == null) return
		mutable.update { it.copy(controlling = peer) }
		scope.launch { runCatching { LinkCore.call<LinkAck>("control", LinkRequest(peer = peer)) } }
	}

	fun stopControlling() {
		if (mutable.value.controlling == null) return
		mutable.update { it.copy(controlling = null) }
		scope.launch { runCatching { LinkCore.call<LinkAck>("control") } }
	}

	/** Input or a command from this phone's remote, for the phone it controls. */
	fun send(message: LinkWire) {
		val peer = mutable.value.controlling ?: return
		if (outbox.trySend(peer to message).isFailure) report("The remote is busy.")
	}

	// MARK: Running

	private fun evaluate() {
		scope.launch {
			load()
			val now = mutable.value
			val wanted = now.enabled && foreground && hasNetworkAccess()
			val needed = now.phones.isNotEmpty() || inviting || scanning
			mutable.update { it.copy(unavailable = it.enabled && foreground && !hasNetworkAccess() || seen.value.failed) }
			if (wanted && needed) start() else stop()
		}
	}

	private suspend fun load() = storage.withLock {
		if (id != null) return@withLock
		val vault = withContext(Dispatchers.IO) {
			secrets.read(VAULT)?.let { runCatching { LinkCore.json.decodeFromString<Vault>(it) }.getOrNull() }
				?: Vault(randomId(), emptyList()).also { secrets.write(VAULT, LinkCore.json.encodeToString(it)) }
		}
		id = vault.id
		mutable.update { it.copy(phones = vault.phones) }
	}

	private suspend fun save() = storage.withLock {
		val vault = Vault(id ?: return@withLock, mutable.value.phones)
		withContext(Dispatchers.IO) { secrets.write(VAULT, LinkCore.json.encodeToString(vault)) }
	}

	private fun start() {
		if (run?.isActive == true) return
		val before = run
		run = scope.launch {
			before?.join()
			val me = id ?: return@launch
			try {
				val keys = mutable.value.phones.map { LinkKey(it.id, it.key) }
				LinkCore.call<LinkAck>("configure", LinkRequest(id = me, name = mutable.value.name, phones = keys))
				val port = LinkCore.call<LinkListening>("listen").port
				if (inviting && mutable.value.invite != null) {
					// The listener restarted, which ends the code that was showing.
					mutable.update { it.copy(invite = LinkCore.call<LinkShown>("invite.open")) }
				}
				ready.value = true
				coroutineScope {
					launch { advertise(me, port) }
					launch { browse(me) }
					launch { events() }
					launch { publishStatus() }
				}
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) {
				report(e)
				restartLater()
			} finally {
				ready.value = false
				seen.value = LinkDiscovery()
				mutable.update { it.copy(presence = emptyMap()) }
				withContext(NonCancellable) { runCatching { LinkCore.call<LinkAck>("unlisten") } }
			}
		}
	}

	private fun stop() {
		restart?.cancel()
		restart = null
		run?.cancel()
	}

	/** Makes a failed listener or browser again a little later; a network change can fail them. */
	private fun restartLater() {
		restart?.cancel()
		restart = scope.launch {
			delay(RESTART_MS)
			restart = null
			run?.cancel()
			evaluate()
		}
	}

	private suspend fun advertise(me: String, port: Int) {
		while (true) {
			try {
				advertiseLink(context, me, port)
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { delay(RESTART_MS) }
		}
	}

	private suspend fun browse(me: String) {
		discoverLinkedPhones(context).collect { found ->
			seen.value = found
			mutable.update { it.copy(unavailable = found.failed) }
			runCatching { LinkCore.call<LinkAck>("seen", LinkRequest(peers = found.phones.filter { it.id != me })) }
		}
		// The flow only ends when mDNS failed.
		restartLater()
	}

	/** What this phone shows now, for the bridge to give each connected phone that needs it. */
	private suspend fun publishStatus() {
		var last: LinkStatus? = null
		var sentAt = 0L
		while (true) {
			val status = host.status()
			val now = SystemClock.elapsedRealtime()
			if (status != last || now - sentAt >= STATUS_REFRESH_MS) {
				runCatching { LinkCore.call<LinkAck>("status", LinkRequest(status = status)) }
				last = status
				sentAt = now
			}
			delay(STATUS_POLL_MS)
		}
	}

	private suspend fun events() {
		while (true) {
			val batch = try { LinkCore.call<List<LinkEvent>>("event") }
			catch (e: CancellationException) { throw e }
			catch (_: Exception) { delay(RESTART_MS); continue }
			for (event in batch) handle(event)
		}
	}

	private suspend fun handle(event: LinkEvent) {
		val peer = event.peer
		when (event.type) {
			"connected" -> if (peer != null) mutable.update { it.copy(presence = it.presence + (peer to Presence())) }
			"disconnected" -> if (peer != null) {
				mutable.update { it.copy(presence = it.presence - peer) }
				if (mutable.value.controlling == peer) stopControlling()
			}
			"paired" -> event.phone?.let { phone ->
				add(phone)
				if (inviting) mutable.update { it.copy(inviteOutcome = Outcome.Linked(it.phone(phone.id)?.name ?: phone.name)) }
			}
			"listenerFailed" -> restartLater()
			"message" -> if (peer != null) event.message?.let { message(peer, it) }
		}
	}

	private suspend fun message(peer: String, text: String) {
		val wire = runCatching { LinkCore.json.decodeFromString<LinkWire>(text) }.getOrNull() ?: return
		when (wire.type) {
			"hello" -> wire.name?.let { rename(peer, cleanName(it)) }
			"status" -> wire.status?.let { status ->
				mutable.update { it.copy(presence = it.presence + (peer to Presence(status))) }
			}
			// The bridge has already dropped the phone; this phone's own list follows.
			"unlink" -> forget(peer)
			"command" -> host.handle(wire)
		}
	}

	private suspend fun rename(peer: String, name: String) {
		val phone = mutable.value.phone(peer) ?: return
		if (name.isEmpty() || name == phone.name) return
		mutable.update { it.copy(phones = it.phones.map { saved -> if (saved.id == peer) saved.copy(name = name) else saved }) }
		save()
	}

	/** A newly linked phone; linking a phone again replaces its old key. */
	private suspend fun add(peer: LinkedPeer) {
		val named = cleanName(peer.name).ifEmpty { "Phone" }
		val phone = LinkedPhone(peer.id, named, peer.key, System.currentTimeMillis() / 1000.0)
		mutable.update { it.copy(phones = it.phones.filterNot { saved -> saved.id == peer.id } + phone) }
		save()
	}

	/** Drops a phone that was unlinked, here or by the other phone. */
	private suspend fun forget(peer: String) {
		mutable.update { it.copy(phones = it.phones.filterNot { saved -> saved.id == peer }, presence = it.presence - peer) }
		if (mutable.value.controlling == peer) stopControlling()
		save()
		evaluate()
	}

	private suspend fun awaitReady() = withTimeoutOrNull(READY_MS) { ready.first { it } } == true

	private fun noNetwork() = mutable.update { it.copy(notice = "Marquee couldn’t start looking for linked phones. Check that local network access is on.") }

	private fun report(e: Exception) = report(if (e is LinkFailed) e.message ?: "Linked phone error." else "Couldn’t complete that linked-phone request.")

	private fun report(message: String) = mutable.update { it.copy(notice = message) }

	private fun unreachable(name: String) =
		"Couldn’t reach $name. Keep both phones on the same Wi-Fi, with Marquee open on both and the code still showing."

	/** What the other phones call this one: the name set in Settings, else the profile’s phone. */
	private fun displayName(state: State): String {
		if (state.customName.isNotEmpty()) return state.customName
		val profile = profileName()?.let { cleanName("$it’s ${Build.MODEL}") }.orEmpty()
		return profile.ifEmpty { Build.MODEL }
	}

	private companion object {
		const val VAULT = "vault"
		const val RESTART_MS = 3_000L
		const val READY_MS = 5_000L
		const val FIND_MS = 10_000L
		const val STATUS_POLL_MS = 500L

		/** Core's pacer sends on a change of the playhead and at least every 5 seconds; this only has to keep the bridge current. */
		const val STATUS_REFRESH_MS = 1_000L

		fun randomId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

		/** Core's `LinkName.clean`: one line, at most 40 characters. */
		fun cleanName(name: String): String =
			name.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(40).trim()
	}
}

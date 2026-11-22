package com.nphil.marquee.session

import androidx.compose.runtime.Immutable
import com.nphil.marquee.core.CoreError
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.PlexPin
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.Reach
import com.nphil.marquee.data.AppSettings
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Plex sign-in, the server in use and the active Home profile, kept as the iPhone app's AppSession keeps
 * them. One lives for the whole process on the main thread; screens read [state] and call the actions.
 */
class AppSession(
	private val core: MarqueeCore,
	private val secrets: SecretStore,
	private val settings: AppSettings,
	private val scope: CoroutineScope,
) {
	sealed interface Phase {
		/** The kept sign-in is still being read; the splash screen stays up. */
		data object Restoring : Phase
		data object SignedOut : Phase
		data object SigningIn : Phase
		data object ChoosingServer : Phase
		data object Ready : Phase
		data class Failed(val error: CoreException.Kind) : Phase
	}

	@Immutable
	data class State(
		val phase: Phase = Phase.Restoring,
		/** A sign-in is kept, so a failure can be retried without signing in again. */
		val hasSignIn: Boolean = false,
		val servers: List<PlexServer> = emptyList(),
		val server: PlexServer? = null,
		val user: PlexHomeUser? = null,
		val homeUsers: List<PlexHomeUser> = emptyList(),
		/** The plex.tv/link code on screen while signing in. */
		val linkCode: String? = null,
		/** The plex.tv page that approves the sign-in, open in a Custom Tab while the PIN is polled. */
		val signInPage: String? = null,
		/** The address that answered last, by server. */
		val addresses: Map<String, String> = emptyMap(),
	) {
		val canRetry: Boolean get() = hasSignIn && phase != Phase.Failed(CoreException.Kind.Unauthorized)
		internal val needsProfileVerification: Boolean get() = hasSignIn && user?.uuid.isNullOrBlank()
	}

	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState.asStateFlow()

	private val signInWrites = Mutex()
	private var token: String? = null
	private var signInJob: Job? = null
	private var refreshJob: Job? = null
	private var addressJob: Job? = null

	/** Opens on the kept server, or the server list, and brings the account up to date behind it. */
	suspend fun restore() {
		refreshJob?.cancel()
		val saved = withContext(Dispatchers.IO) { SavedPlexSignIn.load(secrets) }
		val account = settings.account()
		if (saved == null) {
			mutableState.value = State(phase = Phase.SignedOut)
			Diag.info(DiagCategory.Plex, "session.restore", "signedIn" to false)
			return
		}
		val kept = saved.token
		token = kept
		val list = saved.servers
		mutableState.value = State(
			phase = if (list.isEmpty()) Phase.SigningIn else Phase.ChoosingServer,
			hasSignIn = true,
			servers = list,
			user = saved.profile,
			addresses = account.addresses,
		)
		Diag.info(DiagCategory.Plex, "session.restore", "signedIn" to true, "servers" to list.size, "profile" to (saved.profile != null))
		rememberedServer(list, account.serverId)?.let(::connect)
		refreshJob = scope.launch { refreshAccount(kept) }
	}

	private val pagesToOpen = MutableSharedFlow<String>(extraBufferCapacity = 1)

	/** Pages for the activity to open in a Custom Tab: plex.tv's sign-in page, when it starts or is reopened. */
	val signInPages: SharedFlow<String> = pagesToOpen.asSharedFlow()

	/**
	 * Opens plex.tv's sign-in page and polls the PIN until it is approved there. The page has no forward URL:
	 * plex.tv never forwarded back when the browser was already signed in (iPhone, 2026-11), so the activity
	 * comes back over the page itself once [State.signInPage] clears.
	 */
	fun signIn() = startSignIn("page") {
		val pin = core.createPin(strong = true)
		val page = checkNotNull(pin.page)
		mutableState.update { it.copy(signInPage = page) }
		pagesToOpen.emit(page)
		pin
	}

	/** The fallback: a 4-character code entered at plex.tv/link on any device. */
	fun signInWithCode() = startSignIn("code") {
		val pin = core.createPin(strong = false)
		mutableState.update { it.copy(linkCode = pin.code) }
		pin
	}

	/** Opens plex.tv's sign-in page again, for someone who closed it before approving. */
	fun reopenSignInPage() {
		state.value.signInPage?.let(pagesToOpen::tryEmit)
	}

	private fun startSignIn(method: String, createPin: suspend () -> PlexPin) {
		signInJob?.cancel()
		refreshJob?.cancel()
		Diag.info(DiagCategory.Plex, "signin.start", "method" to method)
		mutableState.update { it.copy(phase = Phase.SigningIn, linkCode = null, signInPage = null) }
		signInJob = scope.launch {
			try {
				val token = core.waitForToken(createPin())
				mutableState.update { it.copy(linkCode = null, signInPage = null) }
				completeSignIn(token)
			} catch (e: CoreException) {
				Diag.warning(DiagCategory.Plex, "signin.failed", "method" to method, "kind" to e.kind.name, "status" to e.status)
				mutableState.update { it.copy(phase = Phase.Failed(e.kind), linkCode = null, signInPage = null) }
			}
		}
	}

	fun cancelSignIn() {
		Diag.info(DiagCategory.Plex, "signin.cancel")
		signInJob?.cancel()
		signInJob = null
		refreshJob?.cancel()
		mutableState.update {
			val phase = if (it.hasSignIn) Phase.Failed(CoreException.Kind.Unreachable) else Phase.SignedOut
			it.copy(phase = phase, linkCode = null, signInPage = null)
		}
	}

	/** Checks the kept sign-in again after plex.tv could not be reached. */
	fun retry() {
		Diag.info(DiagCategory.Plex, "session.retry")
		val kept = token
		if (kept == null) {
			mutableState.update { it.copy(phase = Phase.SignedOut) }
			return
		}
		mutableState.update { it.copy(phase = Phase.SigningIn) }
		refreshJob?.cancel()
		refreshJob = scope.launch { refreshAccount(kept) }
	}

	fun choose(server: PlexServer) {
		Diag.info(DiagCategory.Plex, "server.choose", "owned" to server.isOwned, "present" to server.isPresent, "connections" to server.connections.size)
		scope.launch { settings.setServerId(server.id) }
		connect(server)
	}

	/** Asks plex.tv for the servers again, for the server list's refresh. */
	suspend fun reloadServers() {
		val kept = token ?: return
		try {
			val found = core.servers(kept)
			keepSignIn(kept, kept, found, profile = null)
		} catch (e: CoreException) {
			// Offline, the list keeps the servers it has.
			if (token == kept && e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
		}
	}

	/** Asks plex.tv for the Home's profiles again, for the profile list. */
	suspend fun reloadHomeUsers() {
		val kept = token ?: return
		try {
			val profiles = core.homeUsers(kept)
			if (token == kept) mutableState.update { it.copy(homeUsers = profiles) }
		} catch (e: CoreException) {
			if (token == kept && e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
		}
	}

	/**
	 * How the tablet reaches [server]: the address in use for the current server, a probe for the others.
	 * [refresh] probes every address again. Null when plex.tv's list could not be used at all.
	 */
	suspend fun probe(server: PlexServer, refresh: Boolean): Reach? {
		val reach = orNull { core.connect(server, state.value.addresses[server.id], refresh) }
		if (reach == null) {
			Diag.warning(DiagCategory.Plex, "server.probe.failed")
			return null
		}
		logReach("server.probe", reach, "refresh" to refresh)
		if (server.id == state.value.server?.id) reach.connection?.let { keepAddress(server.id, it.uri) }
		return reach
	}

	suspend fun sections(server: PlexServer): List<LibrarySection> {
		try {
			return core.sections(server, state.value.addresses[server.id])
		} catch (e: CoreException) {
			if (e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
			throw e
		}
	}

	/**
	 * Runs [block] for the current server and the address that answered last. A rejected sign-in ends the session
	 * here, as it does for the Library, so the screens that call through here need not.
	 */
	suspend fun <T> onServer(block: suspend (server: PlexServer, remembered: String?) -> T): T {
		val current = state.value
		val server = current.server ?: throw CoreException(CoreError(kind = "noConnections"))
		try {
			return block(server, current.addresses[server.id])
		} catch (e: CoreException) {
			if (e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
			throw e
		}
	}

	/** The signed-in profile's plex.tv token, for what belongs to the person rather than a server: the Watchlist. */
	val accountToken: String? get() = token

	/** Switches to a Home profile; a wrong [pin] throws `ProfilePinRejected` and changes nothing. */
	suspend fun switchUser(profile: PlexHomeUser, pin: String?) {
		val kept = token ?: return
		if (profile.uuid.isBlank()) throw CoreException(CoreError(kind = "decoding", message = "Plex did not identify this profile."))
		val profileToken = try {
			core.switchUser(kept, profile, pin)
		} catch (e: CoreException) {
			Diag.warning(DiagCategory.Plex, "profile.switch.failed", "kind" to e.kind.name, "status" to e.status)
			throw e
		}
		if (token != kept) return
		if (profileToken.isBlank() || (profileToken == kept && profile.uuid != state.value.user?.uuid)) {
			throw CoreException(CoreError(kind = "decoding", message = "Plex did not verify the new profile."))
		}
		val found = core.servers(profileToken)
		// The previous profile's connections carry its token.
		core.reset()
		if (!keepSignIn(kept, profileToken, found, profile)) return
		refreshJob?.cancel()
		Diag.info(DiagCategory.Plex, "profile.switch", "pin" to (pin != null), "servers" to found.size)
		val same = found.firstOrNull { it.id == state.value.server?.id }
		when {
			same != null -> connect(same)
			found.size == 1 -> choose(found.single())
			else -> mutableState.update { it.copy(server = null, phase = Phase.ChoosingServer) }
		}
	}

	/** Forgets the sign-in. The returned job finishes clearing the stored account and its connections. */
	fun signOut(): Job {
		Diag.notice(DiagCategory.Plex, "signout")
		signInJob?.cancel()
		refreshJob?.cancel()
		addressJob?.cancel()
		token = null
		mutableState.value = State(phase = Phase.SignedOut)
		return scope.launch {
			signInWrites.withLock {
				if (token == null) {
					withContext(Dispatchers.IO) { secrets.clear() }
					settings.clearAccount()
				}
			}
			orNull { core.reset() }
		}
	}

	private suspend fun completeSignIn(newToken: String) {
		val previous = token
		val found = core.servers(newToken)
		val account = orNull { core.account(newToken) }
		val profiles = orNull { core.homeUsers(newToken) }.orEmpty()
		if (!keepSignIn(previous, newToken, found, account, profiles)) return
		Diag.info(DiagCategory.Plex, "signin.done", "servers" to found.size, "profiles" to profiles.size)
		show(found)
	}

	/** Brings a kept sign-in up to date: the servers (their tokens can change), the profile and the Home. */
	private suspend fun refreshAccount(kept: String) {
		try {
			val found = core.servers(kept)
			val account = orNull { core.account(kept) }
			val profiles = orNull { core.homeUsers(kept) }
			if (!keepSignIn(kept, kept, found, account, profiles)) return
			val current = state.value
			when (current.phase) {
				Phase.Ready -> {
					// The open screens keep their connection unless the server's token changed.
					val fresh = found.firstOrNull { it.id == current.server?.id }
					if (fresh != null && fresh.accessToken != current.server?.accessToken) connect(fresh)
				}
				Phase.SigningIn, Phase.ChoosingServer -> show(found)
				else -> Unit
			}
		} catch (e: CoreException) {
			if (token != kept) return
			Diag.notice(DiagCategory.Plex, "session.refresh.failed", "kind" to e.kind.name, "status" to e.status)
			when {
				e.kind == CoreException.Kind.Unauthorized -> signedOutByPlex()
				// Offline, the kept servers stay usable; only a launch with nothing kept reports it.
				state.value.phase == Phase.SigningIn -> mutableState.update { it.copy(phase = Phase.Failed(e.kind)) }
			}
		}
	}

	private suspend fun show(found: List<PlexServer>) {
		val remembered = rememberedServer(found, settings.account().serverId)
		if (remembered != null) {
			choose(remembered)
		} else {
			mutableState.update { it.copy(server = null, phase = Phase.ChoosingServer) }
		}
	}

	private fun rememberedServer(list: List<PlexServer>, serverId: String?): PlexServer? =
		list.firstOrNull { it.id == serverId } ?: list.singleOrNull()

	private fun connect(server: PlexServer) {
		mutableState.update { it.copy(server = server, phase = Phase.Ready) }
		addressJob?.cancel()
		// Finds the address now, so the first screen does not wait for it, and keeps it for the next launch.
		addressJob = scope.launch {
			val reach = orNull { core.connect(server, state.value.addresses[server.id]) }
			if (reach == null) Diag.warning(DiagCategory.Plex, "server.connect.failed") else logReach("server.connect", reach)
			reach?.connection?.let { keepAddress(server.id, it.uri) }
		}
	}

	private suspend fun keepAddress(serverId: String, uri: String) {
		if (state.value.addresses[serverId] == uri) return
		mutableState.update { it.copy(addresses = it.addresses + (serverId to uri)) }
		settings.setAddress(serverId, uri)
	}

	/** What a probe found, as the diagnostic log shows it: the address in use and a line for every address tried. */
	private fun logReach(name: String, reach: Reach, vararg extra: Pair<String, Any?>) {
		Diag.info(DiagCategory.Plex, name, "inUse" to reach.inUse, *extra)
		reach.lines.forEach { Diag.info(DiagCategory.Plex, "$name.line", "line" to it) }
	}

	private fun signedOutByPlex() {
		Diag.warning(DiagCategory.Plex, "session.unauthorized")
		refreshJob?.cancel()
		mutableState.update { it.copy(phase = Phase.Failed(CoreException.Kind.Unauthorized)) }
	}

	private suspend fun keepSignIn(
		expected: String?, newToken: String, servers: List<PlexServer>, profile: PlexHomeUser?,
		profiles: List<PlexHomeUser>? = null,
	): Boolean = signInWrites.withLock {
		if (token != expected || newToken.isBlank()) return false
		val user = profile ?: state.value.user.takeIf { expected == newToken }
		withContext(NonCancellable) {
			withContext(Dispatchers.IO) { SavedPlexSignIn(newToken, servers, user).save(secrets) }
			if (token != expected) return@withContext false
			token = newToken
			mutableState.update {
				it.copy(hasSignIn = true, servers = servers, user = user, homeUsers = profiles ?: it.homeUsers)
			}
			true
		}
	}

	/** [block]'s value, or null when Core reports a failure. Cancellation still propagates. */
	private inline fun <T> orNull(block: () -> T): T? = try {
		block()
	} catch (_: CoreException) {
		null
	}

}

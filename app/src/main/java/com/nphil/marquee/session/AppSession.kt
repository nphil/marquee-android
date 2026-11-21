package com.nphil.marquee.session

import androidx.compose.runtime.Immutable
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.Reach
import com.nphil.marquee.data.AppSettings
import com.nphil.marquee.data.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

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
		/** The address that answered last, by server. */
		val addresses: Map<String, String> = emptyMap(),
	) {
		val canRetry: Boolean get() = hasSignIn && phase != Phase.Failed(CoreException.Kind.Unauthorized)
	}

	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState.asStateFlow()

	private var token: String? = null
	private var signInJob: Job? = null
	private var refreshJob: Job? = null
	private var addressJob: Job? = null

	/** Opens on the kept server, or the server list, and brings the account up to date behind it. */
	suspend fun restore() {
		val (kept, servers) = withContext(Dispatchers.IO) {
			secrets.read(TOKEN) to secrets.read(SERVERS)?.let { runCatching { CoreJson.decodeFromString(serverList, it) }.getOrNull() }
		}
		val account = settings.account()
		if (kept == null) {
			mutableState.value = State(phase = Phase.SignedOut)
			return
		}
		token = kept
		val list = servers.orEmpty()
		mutableState.value = State(
			phase = if (list.isEmpty()) Phase.SigningIn else Phase.ChoosingServer,
			hasSignIn = true,
			servers = list,
			user = account.profile,
			addresses = account.addresses,
		)
		rememberedServer(list, account.serverId)?.let(::connect)
		refreshJob = scope.launch { refreshAccount(kept) }
	}

	/** Shows a plex.tv/link code and finishes once it is entered there. */
	fun signIn() {
		signInJob?.cancel()
		refreshJob?.cancel()
		mutableState.update { it.copy(phase = Phase.SigningIn, linkCode = null) }
		signInJob = scope.launch {
			try {
				val code = core.createLinkCode()
				mutableState.update { it.copy(linkCode = code.code) }
				completeSignIn(core.waitForToken(code))
			} catch (e: CoreException) {
				mutableState.update { it.copy(phase = Phase.Failed(e.kind), linkCode = null) }
			}
		}
	}

	fun cancelSignIn() {
		signInJob?.cancel()
		signInJob = null
		mutableState.update { it.copy(phase = Phase.SignedOut, linkCode = null) }
	}

	/** Checks the kept sign-in again after plex.tv could not be reached. */
	fun retry() {
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
		scope.launch { settings.setServerId(server.id) }
		connect(server)
	}

	/** Asks plex.tv for the servers again, for the server list's refresh. */
	suspend fun reloadServers() {
		val kept = token ?: return
		try {
			val found = core.servers(kept)
			keepServers(found)
			mutableState.update { it.copy(servers = found) }
		} catch (e: CoreException) {
			// Offline, the list keeps the servers it has.
			if (e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
		}
	}

	/** Asks plex.tv for the Home's profiles again, for the profile list. */
	suspend fun reloadHomeUsers() {
		val kept = token ?: return
		try {
			val profiles = core.homeUsers(kept)
			mutableState.update { it.copy(homeUsers = profiles) }
		} catch (e: CoreException) {
			if (e.kind == CoreException.Kind.Unauthorized) signedOutByPlex()
		}
	}

	/**
	 * How the tablet reaches [server]: the address in use for the current server, a probe for the others.
	 * [refresh] probes every address again. Null when plex.tv's list could not be used at all.
	 */
	suspend fun probe(server: PlexServer, refresh: Boolean): Reach? {
		val reach = orNull { core.connect(server, state.value.addresses[server.id], refresh) } ?: return null
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

	/** Switches to a Home profile; a wrong [pin] throws `ProfilePinRejected` and changes nothing. */
	suspend fun switchUser(profile: PlexHomeUser, pin: String?) {
		val kept = token ?: return
		val profileToken = core.switchUser(kept, profile, pin)
		val found = core.servers(profileToken)
		keepSignIn(profileToken, found)
		settings.setProfile(profile)
		// The previous profile's connections carry its token.
		core.reset()
		mutableState.update { it.copy(servers = found, user = profile) }
		val same = found.firstOrNull { it.id == state.value.server?.id }
		when {
			same != null -> connect(same)
			found.size == 1 -> choose(found.single())
			else -> mutableState.update { it.copy(server = null, phase = Phase.ChoosingServer) }
		}
	}

	/** Forgets the sign-in: the token and the server list are deleted. Settings and themes stay. */
	fun signOut() {
		signInJob?.cancel()
		refreshJob?.cancel()
		addressJob?.cancel()
		token = null
		mutableState.value = State(phase = Phase.SignedOut)
		scope.launch {
			withContext(Dispatchers.IO) { secrets.clear() }
			settings.clearAccount()
			orNull { core.reset() }
		}
	}

	private suspend fun completeSignIn(newToken: String) {
		val found = core.servers(newToken)
		val account = orNull { core.account(newToken) }
		val profiles = orNull { core.homeUsers(newToken) }.orEmpty()
		keepSignIn(newToken, found)
		val user = account ?: profiles.firstOrNull { it.isAdmin }
		settings.setProfile(user)
		mutableState.update {
			it.copy(hasSignIn = true, servers = found, homeUsers = profiles, user = user, linkCode = null)
		}
		show(found)
	}

	/** Brings a kept sign-in up to date: the servers (their tokens can change), the profile and the Home. */
	private suspend fun refreshAccount(kept: String) {
		try {
			val found = core.servers(kept)
			val account = orNull { core.account(kept) }
			val profiles = orNull { core.homeUsers(kept) }
			keepServers(found)
			if (account != null) settings.setProfile(account)
			mutableState.update {
				it.copy(servers = found, user = account ?: it.user, homeUsers = profiles ?: it.homeUsers)
			}
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
			reach?.connection?.let { keepAddress(server.id, it.uri) }
		}
	}

	private suspend fun keepAddress(serverId: String, uri: String) {
		if (state.value.addresses[serverId] == uri) return
		mutableState.update { it.copy(addresses = it.addresses + (serverId to uri)) }
		settings.setAddress(serverId, uri)
	}

	private fun signedOutByPlex() {
		refreshJob?.cancel()
		mutableState.update { it.copy(phase = Phase.Failed(CoreException.Kind.Unauthorized)) }
	}

	private suspend fun keepSignIn(newToken: String, servers: List<PlexServer>) {
		token = newToken
		withContext(Dispatchers.IO) {
			secrets.write(TOKEN, newToken)
			secrets.write(SERVERS, CoreJson.encodeToString(serverList, servers))
		}
	}

	private suspend fun keepServers(servers: List<PlexServer>) {
		withContext(Dispatchers.IO) { secrets.write(SERVERS, CoreJson.encodeToString(serverList, servers)) }
	}

	/** [block]'s value, or null when Core reports a failure. Cancellation still propagates. */
	private inline fun <T> orNull(block: () -> T): T? = try {
		block()
	} catch (_: CoreException) {
		null
	}

	private companion object {
		const val TOKEN = "plex.token"
		const val SERVERS = "plex.servers"
		val serverList = ListSerializer(PlexServer.serializer())
	}
}

package com.nphil.marquee.seerr

import android.os.SystemClock
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.SeerrCatalogue
import com.nphil.marquee.core.SeerrDetails
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrGenre
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.core.SeerrLink
import com.nphil.marquee.core.SeerrNamed
import com.nphil.marquee.core.SeerrPage
import com.nphil.marquee.core.SeerrParsed
import com.nphil.marquee.core.SeerrProvider
import com.nphil.marquee.core.SeerrReply
import com.nphil.marquee.core.SeerrRequest
import com.nphil.marquee.core.SeerrTitle
import com.nphil.marquee.core.seerrBrowse
import com.nphil.marquee.core.seerrCatalogue
import com.nphil.marquee.core.seerrDetails
import com.nphil.marquee.core.seerrDiscover
import com.nphil.marquee.core.seerrGenreCards
import com.nphil.marquee.core.seerrGenres
import com.nphil.marquee.core.seerrLanguages
import com.nphil.marquee.core.seerrParse
import com.nphil.marquee.core.seerrRequest
import com.nphil.marquee.core.seerrRequests
import com.nphil.marquee.core.seerrSearch
import com.nphil.marquee.core.seerrSignIn
import com.nphil.marquee.core.seerrVersion
import com.nphil.marquee.core.seerrWatchProviders
import com.nphil.marquee.core.seerrWatchRegions
import com.nphil.marquee.data.AppSettings
import com.nphil.marquee.data.SecretStore
import com.nphil.marquee.data.SeerrPrefs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.session.AppSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Seerr, the integration behind Requests. It is off until the owner turns it on in Settings › Integrations › Seerr,
 * and then only while it has an address (and, signing in with a key, the key). Every call here reads the saved
 * connection, so a screen never holds one. A Plex sign-in's cookie is kept for the profile that made it, so the next
 * launch need not sign in again.
 */
class Seerr(
	private val core: MarqueeCore,
	private val settings: AppSettings,
	private val secrets: SecretStore,
	private val session: AppSession,
	private val scope: CoroutineScope,
) {
	/** The saved connection. [apiKey] null signs in with the Plex profile in use. */
	data class Config(
		val address: String = "",
		val apiKey: String? = null,
		val pulsarr: Boolean = false,
		val on: Boolean = false,
		/** The saved settings have been read. */
		val loaded: Boolean = false,
	) {
		val configured: Boolean get() = address.isNotEmpty()
		val enabled: Boolean get() = on && configured
	}

	/** What a test of the connection found. */
	data class Connection(val version: String, val user: String)

	private val mutableConfig = MutableStateFlow(Config())
	val config: StateFlow<Config> = mutableConfig.asStateFlow()

	/** Whether Requests exists: on, with an address. */
	val enabled: StateFlow<Boolean> = config.map { it.enabled }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

	private val mutableSent = MutableStateFlow(0)

	/** Counts the requests sent, answered or already made; lists showing badges load again when it changes. */
	val requestsSent: StateFlow<Int> = mutableSent.asStateFlow()

	private val loadedConfig = CompletableDeferred<Unit>()
	private val writes = Mutex()
	@Volatile
	private var kept: Kept? = null
	private val catalogue = scope.async(start = CoroutineStart.LAZY) { core.seerrCatalogue() }

	init {
		scope.launch {
			val prefs = settings.seerr.first()
			val (key, cookie) = withContext(Dispatchers.IO) { secrets.read(KEY) to secrets.read(SESSION) }
			kept = cookie?.let { runCatching { CoreJson.decodeFromString(Kept.serializer(), it) }.getOrNull() }
			mutableConfig.value = Config(prefs.address, key, prefs.pulsarr, prefs.enabled, loaded = true)
			loadedConfig.complete(Unit)
		}
	}

	// MARK: Settings

	/** Core's verdict on what was typed: [SeerrParsed.problem] says what is wrong, [SeerrParsed.address] is the address to keep. */
	suspend fun parse(address: String, apiKey: String?): SeerrParsed = core.seerrParse(address, apiKey)

	/** Keeps a connection [parse] accepted. A changed address or key drops the kept sign-in. */
	suspend fun save(address: String, apiKey: String?) {
		loadedConfig.await()
		// A half-written change (address kept, key not) would sign in wrongly, so a cancelled caller cannot stop it midway.
		withContext(NonCancellable) {
			writes.withLock {
				val before = mutableConfig.value
				if (before.address == address && before.apiKey == apiKey) return@withContext
				mutableConfig.update { it.copy(address = address, apiKey = apiKey) }
				kept = null
				settings.updateSeerr { it.copy(address = address) }
				withContext(Dispatchers.IO) {
					secrets.write(KEY, apiKey)
					secrets.write(SESSION, null)
				}
			}
		}
	}

	suspend fun setOn(on: Boolean) {
		loadedConfig.await()
		writes.withLock {
			mutableConfig.update { it.copy(on = on) }
			settings.updateSeerr { it.copy(enabled = on) }
		}
	}

	suspend fun setPulsarr(on: Boolean) {
		loadedConfig.await()
		writes.withLock {
			mutableConfig.update { it.copy(pulsarr = on) }
			settings.updateSeerr { it.copy(pulsarr = on) }
		}
	}

	/** Removes the address, the key, the kept sign-in and the switches; Requests goes away. */
	suspend fun forget() {
		loadedConfig.await()
		// A half-written change (address kept, key not) would sign in wrongly, so a cancelled caller cannot stop it midway.
		withContext(NonCancellable) {
			writes.withLock {
				mutableConfig.value = Config(loaded = true)
				kept = null
				settings.updateSeerr { SeerrPrefs() }
				withContext(Dispatchers.IO) {
					secrets.write(KEY, null)
					secrets.write(SESSION, null)
				}
			}
		}
		Diag.info(DiagCategory.Integration, "seerr.forget")
	}

	/** Proves the saved address and signs in: Seerr's version first, then who this is. */
	suspend fun test(): Connection {
		val version = call("version") { core.seerrVersion(it) }
		val user = call("signIn") { core.seerrSignIn(it) }
		return Connection(version, user.name)
	}

	// MARK: Requests

	/** The Discover rows in order, and the names the browse menu uses. Core fixes them; Seerr is not asked. */
	suspend fun catalogue(): SeerrCatalogue = catalogue.await()

	suspend fun discover(shelf: String): List<SeerrTitle> = call("discover") { core.seerrDiscover(it, shelf) }

	suspend fun browse(feed: SeerrFeed, page: Int): SeerrPage = call("browse") { core.seerrBrowse(it, feed, page) }

	suspend fun genreCards(kind: SeerrKind): List<SeerrGenre> = call("genreCards") { core.seerrGenreCards(it, kind) }

	suspend fun genres(kind: SeerrKind): List<SeerrGenre> = call("genres") { core.seerrGenres(it, kind) }

	suspend fun languages(): List<SeerrNamed> = call("languages") { core.seerrLanguages(it) }

	suspend fun watchRegions(): List<SeerrNamed> = call("watchRegions") { core.seerrWatchRegions(it) }

	suspend fun watchProviders(kind: SeerrKind, region: String): List<SeerrProvider> =
		call("watchProviders") { core.seerrWatchProviders(it, kind, region) }

	suspend fun search(text: String): List<SeerrTitle> = call("search") { core.seerrSearch(it, text) }

	suspend fun details(kind: SeerrKind, tmdbId: Int): SeerrDetails {
		loadedConfig.await()
		val pulsarr = mutableConfig.value.pulsarr
		val server = session.state.value.server?.clientIdentifier
		return call("details") { core.seerrDetails(it, kind, tmdbId, pulsarr, server) }
	}

	/**
	 * Asks for a title; for a series, [seasons] are the ones chosen. The lists showing badges are told, whether the
	 * request went through or found everything already requested.
	 */
	suspend fun request(kind: SeerrKind, tmdbId: Int, seasons: Set<Int>, is4K: Boolean) {
		try {
			call("request") { core.seerrRequest(it, kind, tmdbId, seasons, is4K) }
			mutableSent.update { it + 1 }
		} catch (e: CoreException) {
			if (SeerrText.alreadyRequested(e)) mutableSent.update { it + 1 }
			throw e
		}
	}

	suspend fun requests(): List<SeerrRequest> = call("requests") { core.seerrRequests(it) }

	// MARK: Connection

	/** The connection a call was made with, and the profile it was made as. */
	private class Linked(val link: SeerrLink, val owner: String?)

	private suspend fun linked(): Linked {
		loadedConfig.await()
		val config = mutableConfig.value
		check(config.configured) { "Seerr has no address." }
		val owner = session.state.value.user?.uuid
		val plex = config.apiKey == null
		val cookie = kept?.takeIf { plex && it.owner == owner && it.address == config.address }?.cookie
		return Linked(SeerrLink(config.address, config.apiKey, if (plex) session.accountToken else null, cookie), owner)
	}

	private suspend fun <T> call(name: String, block: suspend (SeerrLink) -> SeerrReply<T>): T {
		val linked = linked()
		val started = SystemClock.elapsedRealtime()
		try {
			val reply = block(linked.link)
			reply.session?.let { keep(it, linked) }
			Diag.info(DiagCategory.Integration, "seerr.$name", "ms" to SystemClock.elapsedRealtime() - started)
			return reply.value
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			// The key and the cookie never reach the log; the failure is named by Core.
			Diag.warning(
				DiagCategory.Integration, "seerr.$name.failed",
				"failure" to ((e as? CoreException)?.failure ?: e::class.simpleName),
				"ms" to SystemClock.elapsedRealtime() - started,
			)
			throw e
		}
	}

	/** Keeps a Plex sign-in's cookie, unless the connection or the profile changed while the call was out. */
	private suspend fun keep(cookie: String, linked: Linked) {
		writes.withLock {
			val config = mutableConfig.value
			if (config.address != linked.link.address || config.apiKey != linked.link.apiKey) return
			if (session.state.value.user?.uuid != linked.owner) return
			val next = Kept(linked.owner, config.address, cookie)
			kept = next
			withContext(Dispatchers.IO) { secrets.write(SESSION, CoreJson.encodeToString(Kept.serializer(), next)) }
		}
	}

	/** A Plex sign-in's cookie, valid for one profile at one address. */
	@Serializable
	private class Kept(val owner: String?, val address: String, val cookie: String)

	private companion object {
		const val KEY = "marquee.seerr.key"
		const val SESSION = "marquee.seerr.session"
	}
}

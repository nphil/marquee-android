package com.nphil.marquee.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * MarqueeCore, the iPhone app's Swift core, as typed suspend calls. Each runs on [Dispatchers.Default];
 * cancelling the caller cancels the Swift task. Failures throw [CoreException].
 */
class MarqueeCore(private val identity: suspend () -> ClientIdentity) {
	private val setup = Mutex()

	@Volatile
	private var configured = false

	/** A plex.tv PIN: a [strong] one comes with the plex.tv page that approves it, a short one is a plex.tv/link code. */
	suspend fun createPin(strong: Boolean): PlexPin = call("pin.create", buildJsonObject { put("strong", strong) })

	/** Waits until the PIN is approved at plex.tv and returns the account token. */
	suspend fun waitForToken(pin: PlexPin): String =
		call<Token>("pin.wait", CoreJson.encodeToJsonElement(PlexPin.serializer(), pin)).token

	suspend fun account(token: String): PlexHomeUser = call("plex.account", tokenParams(token))

	/** The account's servers, its Plex Home's and those shared with it. */
	suspend fun servers(token: String): List<PlexServer> = call("plex.servers", tokenParams(token))

	suspend fun homeUsers(token: String): List<PlexHomeUser> = call("plex.homeUsers", tokenParams(token))

	/** Switches to a Home profile and returns its token. A wrong [pin] throws `ProfilePinRejected`. */
	suspend fun switchUser(token: String, user: PlexHomeUser, pin: String?): String =
		call<Token>("plex.switchUser", buildJsonObject {
			put("token", token)
			put("user", CoreJson.encodeToJsonElement(PlexHomeUser.serializer(), user))
			if (pin != null) put("pin", pin)
		}).token

	/**
	 * Finds the address that answers for [server], trying [remembered] alone first. [refresh] probes every
	 * address again. A server that nothing answers for comes back without a connection.
	 */
	suspend fun connect(server: PlexServer, remembered: String?, refresh: Boolean = false): Reach =
		call("server.connect", serverParams(server, remembered, refresh))

	suspend fun sections(server: PlexServer, remembered: String?): List<LibrarySection> =
		call("library.sections", serverParams(server, remembered, refresh = false))

	/** Drops the bridge's server connections, which hold the tokens of a sign-in that is gone. */
	suspend fun reset() {
		call<JsonObject>("reset", JsonObject(emptyMap()))
	}

	/**
	 * One typed bridge call, decoded on [Dispatchers.Default]. The screens' own files (DetailCore.kt, ...) add their
	 * calls as extensions of this class.
	 */
	internal suspend inline fun <reified T> call(method: String, params: JsonElement): T =
		withContext(Dispatchers.Default) { CoreJson.decodeFromJsonElement<T>(request(method, params)) }

	@PublishedApi
	internal suspend fun request(method: String, params: JsonElement): JsonElement {
		configure()
		return send(method, params)
	}

	private suspend fun configure() {
		if (configured) return
		setup.withLock {
			if (configured) return
			val identity = CoreJson.encodeToJsonElement(ClientIdentity.serializer(), identity())
			send("configure", buildJsonObject { put("identity", identity) })
			configured = true
		}
	}

	/** The `ok` value of the bridge's reply. */
	private suspend fun send(method: String, params: JsonElement): JsonElement {
		val payload = CoreJson.encodeToString(JsonElement.serializer(), params).encodeToByteArray()
		val reply = CoreJson.parseToJsonElement(NativeCore.call(method, payload).decodeToString()).jsonObject
		reply["error"]?.let { throw CoreException(CoreJson.decodeFromJsonElement(CoreError.serializer(), it)) }
		return reply["ok"] ?: JsonNull
	}

	private fun tokenParams(token: String) = buildJsonObject { put("token", token) }

	/** The envelope of a call to one server: the server, the address that worked last, and what the call adds. */
	internal fun serverParams(
		server: PlexServer, remembered: String?, refresh: Boolean = false, extra: JsonObjectBuilder.() -> Unit = {},
	) = buildJsonObject {
		put("server", CoreJson.encodeToJsonElement(PlexServer.serializer(), server))
		if (remembered != null) put("remembered", remembered)
		if (refresh) put("refresh", true)
		extra()
	}

	@Serializable
	private class Token(val token: String)
}

/** A MarqueeCore failure; [kind] is the PlexError case or the bridge's own condition. */
class CoreException internal constructor(error: CoreError) : Exception(error.message ?: error.kind) {
	enum class Kind { Unauthorized, Unreachable, Server, Decoding, PinExpired, ProfilePinRejected, NoConnections, Other }

	val kind: Kind = when (error.kind) {
		"unauthorized" -> Kind.Unauthorized
		"unreachable" -> Kind.Unreachable
		"server" -> Kind.Server
		"decoding" -> Kind.Decoding
		"pinExpired" -> Kind.PinExpired
		"profilePINRejected" -> Kind.ProfilePinRejected
		"noConnections" -> Kind.NoConnections
		else -> Kind.Other
	}

	/** The HTTP status of a `Server` failure. */
	val status: Int? = error.status
}

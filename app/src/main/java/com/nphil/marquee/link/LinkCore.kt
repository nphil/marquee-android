package com.nphil.marquee.link

import com.nphil.marquee.core.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Linked phones (DESIGN §8.10) as the bridge speaks them. Core owns the wire: the pairing code, the keys, the two
 * Noise handshakes, the frames and the messages. This side owns what Android owns: the saved phones, mDNS, the
 * screens and what this phone shows and does as the driving phone. A message crosses as the JSON text of
 * `LinkMessage`, so it is exactly what an iPhone writes and reads.
 */
@Serializable
internal data class LinkRequest(
	val id: String? = null, val name: String? = null, val phones: List<LinkKey>? = null, val peers: List<LinkSeen>? = null,
	val peer: String? = null, val url: String? = null, val host: String? = null, val port: Int? = null,
	val message: String? = null, val status: LinkStatus? = null, val unlink: Boolean? = null, val loopback: Boolean? = null,
)

/** A linked phone as the bridge needs it: its id and the key both phones derived, in base64. */
@Serializable
internal data class LinkKey(val id: String, val key: String)

/** A phone mDNS found: where to call it. */
@Serializable
internal data class LinkSeen(val id: String, val host: String, val port: Int)

@Serializable
internal data class LinkNowPlaying(
	val title: String, val detail: String? = null, val isPlaying: Boolean, val position: Double, val duration: Double,
	val hasNext: Boolean = false, val subtitles: List<LinkSubtitle> = emptyList(), val subtitle: Int? = null,
)

@Serializable
internal data class LinkSubtitle(val id: Int, val label: String)

/** What a driving phone shows, sent to the phone controlling it. `screen` is `tv` or `glasses` when it drives one. */
@Serializable
internal data class LinkStatus(
	val screen: String? = null, val focus: String? = null, val focusDetail: String? = null, val inPlayer: Boolean = false,
	val nowPlaying: LinkNowPlaying? = null, val volume: Double? = null,
) {
	val isControllable: Boolean get() = screen != null || nowPlaying != null
}

/** A message inside a session, in the link's own JSON form: a `type` and the fields that type uses. */
@Serializable
internal data class LinkWire(
	val type: String, val name: String? = null, val input: String? = null, val move: String? = null,
	val seconds: Double? = null, val command: String? = null, val track: Int? = null, val step: Int? = null,
	val level: Double? = null, val status: LinkStatus? = null,
) {
	companion object {
		fun move(direction: String) = LinkWire("input", input = "move", move = direction)
		fun input(kind: String) = LinkWire("input", input = kind)
		fun command(kind: String, seconds: Double? = null, track: Int? = null, step: Int? = null, level: Double? = null) =
			LinkWire("command", command = kind, seconds = seconds, track = track, step = step, level = level)
	}
}

/** What the bridge reports: `connected`, `disconnected`, `message`, `paired` and `listenerFailed`. */
@Serializable
internal data class LinkEvent(val type: String, val peer: String? = null, val message: String? = null, val phone: LinkedPeer? = null)

/** A phone that just linked with this one, with the key both derived. */
@Serializable
internal data class LinkedPeer(val id: String, val name: String, val key: String)

@Serializable
internal data class LinkCode(val id: String, val name: String)

@Serializable
internal data class LinkShown(val url: String, val id: String, val name: String)

@Serializable
internal data class LinkListening(val port: Int)

@Serializable
internal class LinkAck

internal class LinkFailed(message: String) : Exception(message)

internal object LinkCore {
	val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

	/** `link.<operation>`; `event` waits for the first thing that happens, and cancelling it returns at once. */
	suspend inline fun <reified T> call(operation: String, request: LinkRequest = LinkRequest()): T = withContext(Dispatchers.IO) {
		val reply = NativeCore.call("link.$operation", json.encodeToString(request).encodeToByteArray())
		val body = json.parseToJsonElement(reply.decodeToString()).jsonObject
		val ok = body["ok"]
		if (ok == null) throw LinkFailed(body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
			?: "The linked phone couldn’t complete that action.")
		json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), ok)
	}

	suspend fun send(peer: String, message: LinkWire) =
		call<LinkAck>("send", LinkRequest(peer = peer, message = json.encodeToString(message)))
}

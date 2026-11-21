package com.nphil.marquee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON of Core's types. Swift's synthesized decoding wants every non-optional key, defaults included,
 * and reads a missing optional as nil.
 */
val CoreJson = Json {
	ignoreUnknownKeys = true
	encodeDefaults = true
	explicitNulls = false
}

// MarqueeCore's types in their Codable JSON form (bridge/Sources/MarqueeBridge/Wire.swift). Servers and
// profiles go back to the bridge unchanged, so every field Core encodes is kept.

/** What this install tells Plex about itself, sent with every request. */
@Serializable
data class ClientIdentity(
	val clientIdentifier: String,
	val product: String,
	val version: String,
	val platform: String,
	val platformVersion: String,
	val device: String,
	val deviceName: String,
)

/** A plex.tv PIN and when it stops working, in seconds since 1970. A strong one carries [page], the plex.tv page that approves it. */
@Serializable
data class PlexPin(val id: Long, val code: String, val expiresAt: Double? = null, val page: String? = null)

@Serializable
data class PlexConnection(
	val uri: String,
	val isLocal: Boolean,
	val isRelay: Boolean = false,
	val isIPv6: Boolean = false,
)

@Serializable
data class PlexServer(
	val name: String,
	/** The server's machine identifier. */
	val clientIdentifier: String,
	val accessToken: String? = null,
	val isOwned: Boolean,
	/** Belongs to the account's Plex Home. */
	val isHome: Boolean,
	val ownerName: String? = null,
	val isPresent: Boolean,
	val httpsRequired: Boolean,
	val productVersion: String? = null,
	val connections: List<PlexConnection>,
) {
	val id: String get() = clientIdentifier
}

/** A Plex Home profile, or the account itself. */
@Serializable
data class PlexHomeUser(
	val id: Long,
	val uuid: String,
	val title: String,
	val thumb: String? = null,
	@SerialName("admin") val isAdmin: Boolean,
	/** Switching to this profile needs its PIN. */
	@SerialName("protected") val isProtected: Boolean,
	/** A managed profile with content restrictions. */
	@SerialName("restricted") val isRestricted: Boolean,
	@SerialName("guest") val isGuest: Boolean,
)

/** How the tablet reaches a server, from one probe round. */
@Serializable
data class Reach(
	val connection: PlexConnection? = null,
	/** "Local 192.168.1.69". */
	val inUse: String? = null,
	val lines: List<String> = emptyList(),
)

@Serializable
data class LibrarySection(val key: String, val title: String, val type: String)

@Serializable
internal data class CoreError(
	val kind: String,
	val status: Int? = null,
	val message: String? = null,
	val failure: String? = null,
)

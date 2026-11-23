package com.nphil.marquee.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class ArrKind(val wire: String, val title: String) {
	Radarr("radarr", "Radarr"),
	Sonarr("sonarr", "Sonarr"),
}

/** The API key is sent only to the saved Radarr or Sonarr origin and is never included in logs. */
@Serializable
internal data class ArrLink(val address: String, val apiKey: String) {
	override fun toString() = "ArrLink"
}

@Serializable
internal data class ArrAddress(val address: String? = null, val problem: String? = null)

@Serializable
internal data class ArrConnection(val version: String? = null, val problem: String? = null)

internal suspend fun MarqueeCore.arrAddress(kind: ArrKind, address: String, key: String): ArrAddress =
	call("arr.address", addressParams(kind, address, key))

internal suspend fun MarqueeCore.testArr(kind: ArrKind, address: String, key: String): ArrConnection =
	call("arr.status", addressParams(kind, address, key))

private fun addressParams(kind: ArrKind, address: String, key: String) = buildJsonObject {
	put("kind", kind.wire)
	put("address", address)
	put("key", key)
}

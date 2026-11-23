package com.nphil.marquee.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** HomeLabber's address and read-only Marquee token. The token goes only to the saved address and never into a log. */
@Serializable
internal data class HomeLabberLink(val address: String, val token: String) {
	override fun toString() = "HomeLabberLink"
}

@Serializable
internal data class HomeLabberAddress(val address: String? = null, val problem: String? = null)

/** How far HomeLabber's nightly scoring has come, or why it could not be asked. */
@Serializable
internal data class HomeLabberConnection(
	val enabled: Boolean? = null,
	val scored: Int? = null,
	val total: Int? = null,
	val running: Boolean? = null,
	val problem: String? = null,
)

internal suspend fun MarqueeCore.homeLabberAddress(address: String, token: String): HomeLabberAddress =
	call("homelabber.address", linkParams(address, token))

internal suspend fun MarqueeCore.testHomeLabber(address: String, token: String): HomeLabberConnection =
	call("homelabber.status", linkParams(address, token))

private fun linkParams(address: String, token: String) = buildJsonObject {
	put("address", address)
	put("token", token)
}

package com.nphil.marquee.core

import android.content.Context
import kotlinx.serialization.SerialName
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
	/** Seconds since 1970 of HomeLabber's last scoring run. */
	val lastRun: Double? = null,
	/** The encoder the scores were measured with, such as `h264_vaapi`. */
	val encoder: String? = null,
	val problem: String? = null,
)

/**
 * Which of HomeLabber's two scorings judges this device: its standard VMAF model for a large screen, and its phone
 * model for a phone's size and distance. A download or a stream on a phone is judged by the phone model where
 * HomeLabber has one. The class is the device's, so a tablet in a narrow split-screen window is still a tablet.
 */
@Serializable
internal enum class ScreenClass {
	@SerialName("phone") Phone,
	@SerialName("tablet") Tablet;

	companion object {
		/** The same width the diagnostics call a tablet (`DiagFacts.TABLET_DP`). */
		fun of(smallestWidthDp: Int) = if (smallestWidthDp >= 600) Tablet else Phone

		/** The application's configuration is the device's, not the window's. */
		fun of(context: Context) = of(context.applicationContext.resources.configuration.smallestScreenWidthDp)
	}
}

internal suspend fun MarqueeCore.homeLabberAddress(address: String, token: String): HomeLabberAddress =
	call("homelabber.address", linkParams(address, token))

internal suspend fun MarqueeCore.testHomeLabber(address: String, token: String): HomeLabberConnection =
	call("homelabber.status", linkParams(address, token))

private fun linkParams(address: String, token: String) = buildJsonObject {
	put("address", address)
	put("token", token)
}

package com.nphil.marquee.ui.settings

import com.nphil.marquee.core.HomeLabberConnection
import java.text.NumberFormat

/** The words of the HomeLabber Quality Map page, as the iPhone's page says them. */
internal object HomeLabberText {
	const val INTRO = "HomeLabber measures overnight how each title looks at different sizes. With its address and a Marquee token, " +
		"High, Medium and Low downloads use the measured sizes instead of estimates. Make the token in HomeLabber under Settings, Media quality."
	const val NOT_SET_UP = "Not set up. Add the address and token, then test the connection. The token stays on this device, " +
		"sealed by the Android Keystore like the Plex sign-in. Until then, download sizes are estimated from each title's details."
	const val FORGET_MESSAGE = "The address and token are removed from this device. Download sizes are estimated again."

	fun address(problem: String): String = when (problem) {
		"scheme" -> "The address must start with https:// or http://."
		"plaintext" -> "Plain http:// is only for addresses on your home network. Use https:// for any other."
		"token" -> "That is not a valid token."
		else -> "That is not a valid address."
	}

	fun connection(status: HomeLabberConnection): String {
		if (status.enabled != true) return "Connected · The quality map is off in HomeLabber, so download sizes are estimated."
		val count = NumberFormat.getIntegerInstance()
		val progress = "${count.format(status.scored ?: 0)} of ${count.format(status.total ?: 0)} titles measured"
		return "Connected · $progress${if (status.running == true) ", measuring now" else ""}. The others' download sizes are estimated."
	}

	fun problem(problem: String): String = when (problem) {
		"badToken" -> "HomeLabber refused the token. The address answers, but the token is wrong or was revoked. Make a new one in HomeLabber."
		"notHomeLabber" -> "That is not HomeLabber. The address answers, but not like HomeLabber's quality map. Check the address."
		else -> "Can't reach HomeLabber. No answer from that address. Check it, and that this device is on the home network or Tailscale."
	}
}

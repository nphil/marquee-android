package com.nphil.marquee.ui.settings

import com.nphil.marquee.core.HomeLabberConnection
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The words of the HomeLabber Quality Map page, as the iPhone's page says them. */
internal object HomeLabberText {
	const val INTRO = "HomeLabber measures overnight how each title looks at different sizes. With its address and a Marquee token, " +
		"Auto streams a measured title at the best size the connection carries, and High, Medium and Low downloads use the measured " +
		"sizes instead of estimates. Make the token in HomeLabber under Settings, Media quality. It starts with hlmq_."
	const val NOT_SET_UP = "Not set up. Add the address and token, then test the connection. The token stays on this device, " +
		"sealed by the Android Keystore like the Plex sign-in. Until then, Auto steps down by its usual sizes and download sizes " +
		"are estimated from each title's details."
	const val FORGET_MESSAGE = "The address and token are removed from this device. Auto goes back to its usual steps, " +
		"and download sizes are estimated again."
	const val TOKEN_HINT = "hlmq_…"

	fun address(problem: String): String = when (problem) {
		"scheme" -> "The address must start with https:// or http://."
		"plaintext" -> "Plain http:// is only for addresses on your home network. Use https:// for any other."
		"token" -> "That is not a Marquee token. HomeLabber's start with hlmq_."
		else -> "That is not a valid address."
	}

	fun connection(status: HomeLabberConnection, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
		if (status.enabled != true) {
			return "Connected · The quality map is off in HomeLabber, so Auto steps down by its usual sizes and download sizes are estimated."
		}
		val count = NumberFormat.getIntegerInstance(locale)
		val progress = "${count.format(status.scored ?: 0)} of ${count.format(status.total ?: 0)} titles measured"
		val last = status.lastRun?.let {
			" Last run ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(Instant.ofEpochSecond(it.toLong()))}."
		}.orEmpty()
		return "Connected · $progress${if (status.running == true) ", measuring now" else ""}. " +
			"The others are estimated for downloads and streamed by Auto's usual steps.$last"
	}

	fun problem(problem: String): String = when (problem) {
		"badToken" -> "HomeLabber refused the token. The address answers, but the token is wrong or was revoked. Make a new one in HomeLabber."
		"notHomeLabber" -> "That is not HomeLabber. The address answers, but not like HomeLabber's quality map. Check the address."
		else -> "Can't reach HomeLabber. No answer from that address. Check it, and that this device is on the home network or Tailscale."
	}
}

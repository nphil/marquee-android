package com.nphil.marquee.diagnostics

import java.net.URI

/** The words on Diagnostic Logs and the report sheet; they follow the iPhone's, with this tablet's Keystore for its Keychain. */
internal object DiagText {
	const val INTRO = "Marquee keeps a log of what it does on this tablet. With an ntfy server and topic, it sends the log there, " +
		"where it can be read to find out what went wrong. Anyone who can read the topic can read the log, so use a server " +
		"that asks for an access token. The token stays on this tablet, in the Android Keystore."

	fun options(title: String) = "Messages from this tablet are titled “$title”. Live logs go out every ten seconds while " +
		"Marquee is open, and within a second of a problem. Detailed adds every request and a memory reading every 30 seconds, " +
		"for while a problem is being chased. The floating button stays above everything in Marquee, video included, and " +
		"sends a report with a screenshot."

	const val FORGET_TITLE = "Forget Diagnostic Logs?"
	const val FORGET_MESSAGE = "The server, topic and token are removed from this tablet, and the log is no longer sent. " +
		"It is still kept on this tablet."

	const val NOTE_HINT = "What went wrong? (optional)"
	const val NOTE_FOOTER = "A few words on what happened, and roughly when, help most."
	const val SHOT_FOOTER = "Video can show black in screenshots; the app's details go with the report instead."
	const val REPORT_FAILED = "The report could not be sent. Check Diagnostic Logs, and that this tablet is online."

	val sentWith = listOf(
		"The log of the last day",
		"What is on screen and the app's details",
		"Settings, without passwords or tokens",
	)

	enum class Tone { Neutral, Good, Problem }

	class Line(val text: String, val tone: Tone)

	fun problem(problem: DiagProblem): String = when (problem) {
		DiagProblem.Scheme -> "The server must start with https:// or http://."
		DiagProblem.Host -> "That is not a valid server address."
		DiagProblem.Plaintext -> "Plain http:// is only for servers on your home network. Use https:// for any other."
		DiagProblem.Topic -> "A topic is 1 to 64 letters, digits, - and _."
		DiagProblem.Token -> "That is not a valid token."
	}

	fun fieldOf(problem: DiagProblem): DiagField = when (problem) {
		DiagProblem.Topic -> DiagField.Topic
		DiagProblem.Token -> DiagField.Token
		DiagProblem.Scheme, DiagProblem.Host, DiagProblem.Plaintext -> DiagField.Server
	}

	/** A problem shows once its field has been left, and not while that field is being typed in. */
	fun shownProblem(problem: DiagProblem?, draft: DiagDraft, left: Set<DiagField>, focused: DiagField?): DiagProblem? {
		if (problem == null) return null
		val field = fieldOf(problem)
		val text = when (field) {
			DiagField.Server -> draft.server
			DiagField.Topic -> draft.topic
			DiagField.Token -> draft.token
		}
		return problem.takeIf { text.isNotEmpty() && field in left && field != focused }
	}

	fun status(configured: Boolean, check: DiagCheck): Line = when {
		!configured -> Line(
			"Not set up. Until a server and topic are added, the log stays on this tablet, for about a day.",
			Tone.Neutral,
		)
		check is DiagCheck.Untested -> Line("Not tested yet.", Tone.Neutral)
		check is DiagCheck.Done -> result(check.test)
		else -> Line("Checking… Sending a test message to the topic.", Tone.Neutral)
	}

	private fun result(test: DiagTest): Line = when (test) {
		DiagTest.Sent -> Line("Connected · A test message went to the topic.", Tone.Good)
		DiagTest.NeedsToken -> Line(
			"The server wants an access token, or does not know this one. Check the token.",
			Tone.Problem,
		)
		DiagTest.Refused -> Line(
			"The server refused to let this tablet write to the topic. The topic must accept messages without a token, " +
				"or the token's user needs permission to write to it.",
			Tone.Problem,
		)
		DiagTest.Unreachable -> Line("Can't reach the server. Check the address, and that this tablet is online.", Tone.Problem)
		is DiagTest.Failed -> Line(
			"The server answered with an error${test.status?.let { " ($it)" }.orEmpty()}. Try again in a minute.",
			Tone.Problem,
		)
		DiagTest.NotSetUp -> Line("Add a server and topic first.", Tone.Problem)
	}

	/** What live sending is doing, when it is worth a line; [time] writes a time of day. */
	fun live(status: DiagStatus?, time: (Long) -> String): Line? = when (status?.state) {
		DiagStatus.State.Refused -> Line(
			"Live logs stopped: the server refused them. They start again once a test gets through.",
			Tone.Problem,
		)
		DiagStatus.State.Retrying -> Line(
			"Live logs are waiting for the server. They are kept on this tablet and sent once it answers.",
			Tone.Neutral,
		)
		DiagStatus.State.Sending -> status.lastSentMillis?.let { Line("Live logs last sent ${time(it)}.", Tone.Neutral) }
		DiagStatus.State.Off, null -> null
	}

	fun destination(topic: String, server: String): String {
		val host = runCatching { URI(server.trim()).host }.getOrNull()
		return "It goes to the topic $topic on ${host ?: "the server"}. Anyone who can read that topic can read the report."
	}

	fun sent(id: String, attached: Boolean): String =
		if (attached) "Sent as report $id, with the log attached."
		else "Sent as report $id. The server takes no attachments, so the log's last part went as messages."
}

/** What the messages are called: the name typed in Diagnostic Logs, else one made from the profile and the kind of device. */
internal object DiagNames {
	fun default(profile: String?, tablet: Boolean): String {
		val kind = if (tablet) "tablet" else "phone"
		val owner = profile?.trim().orEmpty()
		return if (owner.isEmpty()) "Android $kind" else "$owner’s $kind"
	}

	fun device(typed: String, profile: String?, tablet: Boolean): String =
		typed.trim().ifEmpty { default(profile, tablet) }

	fun title(version: String, device: String) = "Marquee $version · $device"
}

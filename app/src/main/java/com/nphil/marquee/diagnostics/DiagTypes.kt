package com.nphil.marquee.diagnostics

/** How serious an event is; the numbers are the raw values of Core's DiagLevel, which the bridge reads. */
enum class DiagLevel(val wire: Int) { Debug(0), Info(1), Notice(2), Warning(3), Error(4) }

/** What an event is about; the names are the raw values of Core's DiagCategory, which the bridge reads. */
enum class DiagCategory(val wire: String) {
	App("app"),
	Ui("ui"),
	Nav("nav"),
	Net("net"),
	Plex("plex"),
	Player("player"),
	Audio("audio"),
	Subtitles("subtitles"),
	Offers("offers"),
	Download("download"),
	Live("live"),
	Link("link"),
	BigScreen("bigscreen"),
	Integration("integration"),
}

/** Why an ntfy address, topic or token cannot be saved; the first three belong to the Server field. */
enum class DiagProblem(val wire: String) {
	Scheme("scheme"),
	Host("host"),
	Plaintext("plaintext"),
	Topic("topic"),
	Token("token");

	companion object {
		fun of(wire: String?): DiagProblem? = entries.firstOrNull { it.wire == wire }
	}
}

/** The three fields that name an ntfy topic. */
enum class DiagField { Server, Topic, Token }

/** The server, topic and token as typed. */
data class DiagDraft(val server: String = "", val topic: String = "", val token: String = "") {
	fun with(field: DiagField, value: String): DiagDraft = when (field) {
		DiagField.Server -> copy(server = value)
		DiagField.Topic -> copy(topic = value)
		DiagField.Token -> copy(token = value)
	}
}

/** What the bridge made of a [DiagDraft]: the problem with it, if any, and the host it names. */
class DiagParse(val problem: DiagProblem?, val host: String?)

/** What live sending is doing, as the bridge reports it. */
class DiagStatus(val state: State, val lastSentMillis: Long?, val refusal: Refusal?, val waiting: Boolean) {
	enum class State { Off, Sending, Retrying, Refused }
	enum class Refusal { NeedsToken, Refused }
}

/** The answer to a test message sent to the saved server. */
sealed interface DiagTest {
	data object Sent : DiagTest
	data object NeedsToken : DiagTest
	data object Refused : DiagTest
	data object Unreachable : DiagTest
	data class Failed(val status: Int?) : DiagTest
	data object NotSetUp : DiagTest
}

/** Where Test Connection stands, for the settings page. */
sealed interface DiagCheck {
	data object Untested : DiagCheck
	data object Testing : DiagCheck
	data class Done(val test: DiagTest) : DiagCheck
}

/** The outcome of sending a report. */
sealed interface DiagReportResult {
	data class Sent(val id: String, val attached: Boolean) : DiagReportResult
	data class Failed(val text: String) : DiagReportResult
}

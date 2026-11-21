package com.nphil.marquee.diagnostics

import com.nphil.marquee.core.CoreJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.Base64

/** The shapes of the bridge's `diag.*` calls, as bridge/Sources/MarqueeDiagnostics/DiagRouter.swift reads and writes them. */
internal object DiagWire {
	val none = JsonObject(emptyMap())

	inline fun <reified T> decode(element: JsonElement): T = CoreJson.decodeFromJsonElement<T>(element)

	/** A field's value as the bridge reads it; the bridge writes it as Core writes its own and redacts the text. */
	fun scalar(value: Any?): JsonElement = when (value) {
		null -> JsonNull
		is Boolean -> JsonPrimitive(value)
		is Byte, is Short, is Int, is Long -> JsonPrimitive((value as Number).toLong())
		is Float, is Double -> (value as Number).toDouble().let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
		is Throwable -> JsonPrimitive(describe(value))
		else -> JsonPrimitive(value.toString())
	}

	/** An exception as one line: its class and message. */
	fun describe(error: Throwable): String {
		val name = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
		val message = error.message
		return if (message.isNullOrBlank()) name else "$name: $message"
	}

	fun event(
		timeMillis: Long,
		level: DiagLevel,
		category: DiagCategory,
		name: String,
		fields: Array<out Pair<String, Any?>>,
	): JsonObject = JsonObject(
		mapOf(
			"o" to JsonPrimitive("e"),
			"t" to JsonPrimitive(timeMillis),
			"l" to JsonPrimitive(level.wire),
			"c" to JsonPrimitive(category.wire),
			"n" to JsonPrimitive(name),
			"f" to JsonArray(fields.filter { it.second != null }.map { (key, field) -> JsonArray(listOf(JsonPrimitive(key), scalar(field))) }),
		)
	)

	fun playbackBegin(id: String) = JsonObject(mapOf("o" to JsonPrimitive("b"), "id" to JsonPrimitive(id)))

	val playbackEnd = JsonObject(mapOf("o" to JsonPrimitive("x")))

	fun events(offsetSeconds: Int, ops: List<JsonObject>) =
		JsonObject(mapOf("offset" to JsonPrimitive(offsetSeconds), "ops" to JsonArray(ops)))

	fun phase(name: String) = buildJsonObject { put("phase", name) }

	fun start(folder: String, version: String, build: String, commit: String, bornMillis: Long, offsetSeconds: Int) =
		buildJsonObject {
			put("folder", folder)
			put("version", version)
			put("build", build)
			put("commit", commit)
			put("born", bornMillis)
			put("offset", offsetSeconds)
		}

	/** The server, topic and token only once a server and topic are saved; the log is kept either way. */
	fun configure(config: DiagConfig, title: String, device: String): JsonObject = buildJsonObject {
		if (config.configured) {
			put("server", config.server.trim())
			put("topic", config.topic.trim())
			if (config.token.isNotBlank()) put("token", config.token.trim())
		}
		put("live", config.live)
		put("title", title)
		put("device", device)
		put("detailed", config.detailed)
	}

	fun parse(draft: DiagDraft) = buildJsonObject {
		put("server", draft.server)
		put("topic", draft.topic)
		put("token", draft.token)
	}

	fun report(note: String, source: String, details: String, screen: String, screenshot: ByteArray?) = buildJsonObject {
		put("note", note)
		put("source", source)
		put("details", details)
		put("screen", screen)
		if (screenshot != null) put("screenshot", Base64.getEncoder().encodeToString(screenshot))
	}

	fun journal(maxBytes: Int?) = buildJsonObject { if (maxBytes != null) put("maxBytes", maxBytes) }

	fun status(dto: StateDto) = DiagStatus(
		state = when (dto.state) {
			"sending" -> DiagStatus.State.Sending
			"retrying" -> DiagStatus.State.Retrying
			"refused" -> DiagStatus.State.Refused
			else -> DiagStatus.State.Off
		},
		lastSentMillis = dto.lastSent?.toLong(),
		refusal = when (dto.refusal) {
			"needsToken" -> DiagStatus.Refusal.NeedsToken
			"refused" -> DiagStatus.Refusal.Refused
			else -> null
		},
		waiting = dto.waiting != 0,
	)

	fun test(dto: TestDto): DiagTest = when (dto.result) {
		"sent" -> DiagTest.Sent
		"needsToken" -> DiagTest.NeedsToken
		"refused" -> DiagTest.Refused
		"unreachable" -> DiagTest.Unreachable
		else -> DiagTest.Failed(dto.status)
	}
}

@Serializable
internal class StartedDto(val launch: String)

@Serializable
internal class StateDto(
	val state: String,
	val lastSent: Double? = null,
	val status: Int? = null,
	val refusal: String? = null,
	val waiting: Int = 0,
)

@Serializable
internal class TestDto(val result: String, val status: Int? = null)

@Serializable
internal class ReportDto(val sent: Boolean, val id: String? = null, val attached: Boolean? = null, val text: String? = null)

@Serializable
internal class ParseDto(val problem: String? = null, val host: String? = null)

@Serializable
internal class JournalDto(val text: String)

package com.nphil.marquee.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/** The `n` of an event as the bridge reads it. */
internal fun JsonObject.name(): String = getValue("n").jsonPrimitive.content

/** A bridge that records every call; the first [startFailures] `diag.start` calls fail, as when the library is missing. */
internal class FakeBridge(
	private var startFailures: Int = 0,
	private val replies: Map<String, JsonElement> = emptyMap(),
) : DiagBridge {
	class Call(val method: String, val payload: JsonObject) {
		val ops: List<JsonObject> get() = payload["ops"]?.jsonArray.orEmpty().map { it.jsonObject }
	}

	val calls = CopyOnWriteArrayList<Call>()
	val methods: List<String> get() = calls.map { it.method }

	override suspend fun call(method: String, payload: JsonObject): JsonElement {
		calls += Call(method, payload)
		if (method == "diag.start") {
			if (startFailures > 0) {
				startFailures--
				error("The bridge is not there.")
			}
			return JsonObject(mapOf("launch" to JsonPrimitive("L1")))
		}
		return replies[method] ?: DiagWire.none
	}
}

package com.nphil.marquee.diagnostics

import com.nphil.marquee.core.CoreError
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.NativeCore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One call into the bridge's diagnostic log: the `ok` value of its reply, or a [CoreException]. */
internal fun interface DiagBridge {
	suspend fun call(method: String, payload: JsonObject): JsonElement
}

/** The real bridge, through the same JNI entry as MarqueeCore. */
internal object NativeDiagBridge : DiagBridge {
	override suspend fun call(method: String, payload: JsonObject): JsonElement {
		val bytes = CoreJson.encodeToString(JsonObject.serializer(), payload).encodeToByteArray()
		val reply = CoreJson.parseToJsonElement(NativeCore.call(method, bytes).decodeToString()).jsonObject
		reply["error"]?.let { throw CoreException(CoreJson.decodeFromJsonElement(CoreError.serializer(), it)) }
		return reply["ok"] ?: JsonNull
	}
}

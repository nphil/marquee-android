package com.nphil.marquee.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Same-frequency sharing on LumaTV's tuner, read from `status.json`: viewers on one RF share a tuner, up to
 * [CAPACITY]; another RF displaces them, or is refused while Plex records. Every field is optional, so an older TV
 * reads as "nothing shared" and keeps today's behaviour.
 */
internal object TunerSharing {
	const val CAPACITY = 4
	const val FULL_MESSAGE = "Four devices are already watching this channel group."
	const val RECORDING_MESSAGE = "The TV is recording, so its tuner can't change channel right now."

	/** Whether [number] is on the RF a current viewer is on, so tuning it joins that tuner instead of taking it over. */
	fun shares(status: JsonElement?, number: String): Boolean = viewers(status, number) > 0

	/** The sentence for a 503 on the stream request of channel [number]; null keeps the generic refusal. */
	fun refusalMessage(status: JsonElement?, number: String): String? {
		if (viewers(status, number) >= CAPACITY) return FULL_MESSAGE
		val holder = (status as? JsonObject)?.get("holder") as? JsonObject ?: return null
		return if (holder.text("name")?.contains("plex", ignoreCase = true) == true) RECORDING_MESSAGE else null
	}

	/** Viewers on the RF of [number]: those watching it, or another channel of an RF group that carries it. */
	private fun viewers(status: JsonElement?, number: String): Int {
		val root = status as? JsonObject ?: return 0
		val viewers = root["viewers"] as? JsonArray ?: return 0
		val group = (root["sameRf"] as? JsonArray)?.firstNotNullOfOrNull { item ->
			((item as? JsonObject)?.get("channels") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
				?.takeIf { number in it }
		} ?: listOf(number)
		return viewers.count { (it as? JsonObject)?.text("channel") in group }
	}

	private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

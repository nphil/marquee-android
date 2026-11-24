package com.nphil.marquee.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl

/**
 * Another client or the TV took LumaTV's tuner from this tablet: an entry of the tuner's `status.json` `displaced`
 * list, which the TV keeps for 60 s. Older TVs have no such list.
 */
internal data class TunerTakeover(val by: String, val byTv: Boolean, val atMs: Long) {
	val message: String
		get() = when {
			byTv -> "The TV is now using its tuner."
			by.isBlank() -> "Another device is now using the TV tuner."
			else -> "$by is now using the TV tuner."
		}

	companion object {
		const val WINDOW_MS = 60_000L
		const val NAME_LIMIT = 40

		/** [name] as the TV records it: trimmed and cut to [NAME_LIMIT], which is also how an entry is matched. */
		fun clientName(name: String): String = name.trim().take(NAME_LIMIT)

		/** [url] with `?device=<name>`, percent-encoded. */
		fun named(url: HttpUrl, name: String): HttpUrl =
			clientName(name).takeIf { it.isNotEmpty() }?.let { url.newBuilder().setQueryParameter("device", it).build() } ?: url

		/** The newest entry of [status] that names this client and is no older than [WINDOW_MS]; null for anything else. */
		fun match(status: JsonElement?, name: String, nowMs: Long): TunerTakeover? {
			val mine = clientName(name).takeIf { it.isNotEmpty() } ?: return null
			val entries = (status as? JsonObject)?.get("displaced") as? JsonArray ?: return null
			return entries.mapNotNull { item ->
				val entry = item as? JsonObject ?: return@mapNotNull null
				if (entry.text("name") != mine) return@mapNotNull null
				val at = (entry["at"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: return@mapNotNull null
				if (nowMs - at > WINDOW_MS) return@mapNotNull null
				TunerTakeover(entry.text("by").orEmpty(), entry.text("byKind") == "tv", at)
			}.maxByOrNull { it.atMs }
		}

		private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
	}
}

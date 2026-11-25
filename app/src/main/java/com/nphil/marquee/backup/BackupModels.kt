package com.nphil.marquee.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Core's file format on the wire (MarqueeCore `BackupPayload`): what Android seals, and what it reads back. */
internal val BackupJson = Json {
	ignoreUnknownKeys = true
	encodeDefaults = true
	explicitNulls = false
}

internal object BackupPlatform {
	const val ANDROID = "android"
}

/** One saved setting: where it lives, which group the review lists it under, and its typed value. */
@Serializable
internal data class BackupEntry(
	val store: String,
	val key: String,
	val group: String = BackupGroup.OTHER,
	val item: String? = null,
	val kind: String,
	val value: JsonElement,
) {
	val id: String get() = "$store\u0000$key"

	fun bool(): Boolean? = if (kind == BOOL) (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull else null
	fun int(): Int? = if (kind == INT) (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull else null
	fun double(): Double? = if (kind == DOUBLE) (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull else null
	fun string(): String? = if (kind == STRING) (value as? JsonPrimitive)?.takeIf { it.isString }?.content else null
	fun strings(): Set<String>? {
		if (kind != STRINGS) return null
		val array = value as? JsonArray ?: return null
		val items = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
		return items.toSet()
	}

	companion object {
		const val BOOL = "bool"
		const val INT = "int"
		const val DOUBLE = "double"
		const val STRING = "string"
		const val STRINGS = "strings"

		fun of(store: String, key: String, value: Any, group: String = BackupGroup.OTHER, item: String? = null): BackupEntry? {
			val (kind, json) = when (value) {
				is Boolean -> BOOL to JsonPrimitive(value)
				is Int -> INT to JsonPrimitive(value)
				is Long -> INT to JsonPrimitive(value)
				is Float -> DOUBLE to JsonPrimitive(value.toDouble())
				is Double -> DOUBLE to JsonPrimitive(value)
				is String -> STRING to JsonPrimitive(value)
				// A set has no order; sorting keeps the same settings the same bytes, which the change check relies on.
				is Set<*> -> STRINGS to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
				else -> return null
			}
			return BackupEntry(store, key, group, item, kind, json)
		}
	}
}

/** Core's `BackupGroup` raw values: the lines of the restore review. */
internal object BackupGroup {
	const val PROFILES = "profiles"
	const val APPEARANCE = "appearance"
	const val TABS = "tabs"
	const val SERVERS = "servers"
	const val INTEGRATIONS = "integrations"
	const val REMOTE = "remote"
	const val TVS = "tvs"
	const val BIG_SCREEN = "bigScreen"
	const val LIVE = "live"
	const val TRACKS = "tracks"
	const val OTHER = "other"
}

/** An integration in the form both platforms read: its address, its secret and its options as text. */
@Serializable
internal data class BackupIntegration(
	val address: String? = null,
	val secret: String? = null,
	val options: Map<String, String> = emptyMap(),
)

@Serializable
internal data class BackupPayload(
	val platform: String,
	val appVersion: String,
	val entries: List<BackupEntry> = emptyList(),
	val integrations: Map<String, BackupIntegration> = emptyMap(),
)

/** A line of the restore review: a kind of setting in words, never a value. */
@Serializable
internal data class BackupLine(val title: String, val detail: String? = null)

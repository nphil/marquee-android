package com.nphil.marquee.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What Android keeps that a backup carries, and where each piece is listed in the restore review. Everything in
 * these stores is in by default; the lists below name what is not: a value that identifies this install, the Plex
 * sign-in and its caches, and the layout of this device's window.
 *
 * Stores are named as in [BackupEntry.store]: `settings` and `diagnostics` are the two preference files, `tv_remote`
 * the remote's preferences and `secret.<name>` a sealed [com.nphil.marquee.data.SecretStore].
 */
internal object BackupScope {
	const val SETTINGS = "settings"
	const val DIAGNOSTICS = "diagnostics"
	const val REMOTE = "tv_remote"
	private const val SECRET = "secret."

	fun secretStore(name: String) = SECRET + name
	fun secretName(store: String): String? = store.takeIf { it.startsWith(SECRET) }?.removePrefix(SECRET)

	/** Where one stored value belongs in the review; null when it is not backed up. */
	class Place(val group: String, val item: String? = null)

	private val secrets: Map<Pair<String, String>, String> = mapOf(
		(secretStore("seerr") to "marquee.seerr.key") to "Seerr",
		(secretStore("subtitle_services") to "bazarr") to "Bazarr",
		(secretStore("homelabber") to "homelabber") to "HomeLabber",
		(secretStore("arr_services") to "radarr") to "Radarr",
		(secretStore("arr_services") to "sonarr") to "Sonarr",
		(secretStore("diagnostics") to "ntfy.server") to DIAGNOSTICS_ITEM,
		(secretStore("diagnostics") to "ntfy.topic") to DIAGNOSTICS_ITEM,
		(secretStore("diagnostics") to "ntfy.token") to DIAGNOSTICS_ITEM,
	)

	/** Every sealed value that is in scope, for reading a snapshot of them. */
	val secretKeys: Set<Pair<String, String>> get() = secrets.keys + (secretStore("tv_remote_pairing") to "pairings")

	private val deviceOnly = setOf(
		"plex.clientIdentifier", "plex.addresses", "plex.profile",
		"shell.sidebarExpanded", "shell.sidebarWidth", "player.miniBarHeight",
		// Whether Seerr's check of this sign-in passed is this device's, like the cookie it is about.
		"marquee.seerr.verified",
	)

	fun place(store: String, key: String): Place? = when (store) {
		SETTINGS -> settingsPlace(key)
		DIAGNOSTICS -> if (key == "name") null else Place(BackupGroup.INTEGRATIONS, DIAGNOSTICS_ITEM)
		REMOTE -> when {
			key == "phoneName" -> null
			key.startsWith("layout.") -> Place(BackupGroup.REMOTE)
			key == "enabled" || key == "selected" -> Place(BackupGroup.TVS)
			else -> Place(BackupGroup.OTHER)
		}
		else -> when {
			secretName(store) == "tv_remote_pairing" && key == "pairings" -> Place(BackupGroup.TVS)
			else -> secrets[store to key]?.let { Place(BackupGroup.INTEGRATIONS, it) }
		}
	}

	private fun settingsPlace(key: String): Place? = when {
		key in deviceOnly || key.startsWith("search.recents.") -> null
		key.startsWith("theme.") -> Place(BackupGroup.APPEARANCE)
		key == "shell.tabOrder" || key == "shell.tabsShown" || key == "shell.tabBarBehavior" -> Place(BackupGroup.TABS)
		key == "plex.serverId" -> Place(BackupGroup.SERVERS)
		key == "subtitles.speechDock" -> Place(BackupGroup.INTEGRATIONS, "SpeechDock")
		key.startsWith("marquee.seerr.") -> Place(BackupGroup.INTEGRATIONS, "Seerr")
		key == "cast.enabled" -> Place(BackupGroup.INTEGRATIONS, "Chromecast")
		key == "airplay.enabled" -> Place(BackupGroup.INTEGRATIONS, "AirPlay")
		key.startsWith("marquee.bigscreen.") -> Place(BackupGroup.BIG_SCREEN)
		key.startsWith("live.channels.") -> Place(BackupGroup.LIVE)
		key.startsWith("tracks.") -> Place(BackupGroup.TRACKS)
		profileOf(key) != null -> Place(BackupGroup.PROFILES)
		else -> Place(BackupGroup.OTHER)
	}

	/** The profile a per-profile setting belongs to, or null for a key that is not one. */
	fun profileOf(key: String): String? = when {
		key.startsWith("playback.") -> key.removePrefix("playback.").substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
		key.startsWith("posters.") -> key.removePrefix("posters.").substringAfter('.', "").takeIf { it.isNotEmpty() }
		key.startsWith("subtitles.") && key.endsWith(".look") -> key.removePrefix("subtitles.").removeSuffix(".look").takeIf { it.isNotEmpty() }
		key.startsWith("home.layout.") -> key.removePrefix("home.layout.").takeIf { it.isNotEmpty() }
		key.startsWith("home.rewatchDismissed.") -> key.removePrefix("home.rewatchDismissed.").takeIf { it.isNotEmpty() }
		key.startsWith("library.hidden.") -> key.removePrefix("library.hidden.").takeIf { it.isNotEmpty() }
		key.startsWith("library.chosen.") -> key.removePrefix("library.chosen.").takeIf { it.isNotEmpty() }
		key.startsWith("library.view.") -> key.removePrefix("library.view.").substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
		else -> null
	}

	fun covers(entry: BackupEntry) = place(entry.store, entry.key) != null

	/** A stored value as an entry, or null when it is not backed up or has a type a backup cannot hold. */
	fun entry(store: String, key: String, value: Any): BackupEntry? {
		val place = place(store, key) ?: return null
		return BackupEntry.of(store, key, value, place.group, place.item)
	}

	/** How many profiles the profile settings in [entries] cover; one entry per key would count each setting. */
	fun profileCount(entries: List<BackupEntry>): Int =
		entries.filter { it.group == BackupGroup.PROFILES }.mapNotNull { profileOf(it.key) }.toSet().size

	/** The integrations in [entries] as both platforms read them. An integration without what it needs is left out. */
	fun integrationsFrom(entries: List<BackupEntry>): Map<String, BackupIntegration> {
		val byKey = entries.associateBy { it.store to it.key }
		fun text(store: String, key: String) = byKey[store to key]?.string()
		fun link(store: String, key: String, secretField: String): BackupIntegration? {
			val json = text(store, key)?.let(::parseObject) ?: return null
			val address = json.text("address") ?: return null
			val secret = json.text(secretField) ?: return null
			return BackupIntegration(address, secret)
		}
		val found = linkedMapOf<String, BackupIntegration>()
		text(SETTINGS, "marquee.seerr.address")?.let { address ->
			val pulsarr = byKey[SETTINGS to "marquee.seerr.pulsarr"]?.bool()
			found[Names.SEERR] = BackupIntegration(
				address, text(secretStore("seerr"), "marquee.seerr.key"),
				if (pulsarr == null) emptyMap() else mapOf("pulsarr" to pulsarr.toString()),
			)
		}
		link(secretStore("arr_services"), "radarr", "apiKey")?.let { found[Names.RADARR] = it }
		link(secretStore("arr_services"), "sonarr", "apiKey")?.let { found[Names.SONARR] = it }
		link(secretStore("subtitle_services"), "bazarr", "apiKey")?.let { found[Names.BAZARR] = it }
		text(SETTINGS, "subtitles.speechDock")?.takeIf { it.isNotBlank() }?.let { found[Names.SPEECH_DOCK] = BackupIntegration(it) }
		link(secretStore("homelabber"), "homelabber", "token")?.let { found[Names.HOME_LABBER] = it }
		text(secretStore("diagnostics"), "ntfy.server")?.takeIf { it.isNotBlank() }?.let { server ->
			val topic = text(secretStore("diagnostics"), "ntfy.topic")
			found[Names.DIAGNOSTICS] = BackupIntegration(
				server, text(secretStore("diagnostics"), "ntfy.token"),
				if (topic == null) emptyMap() else mapOf("topic" to topic),
			)
		}
		return found
	}

	/** The entries an iPhone's integrations come to on Android. */
	fun entriesFrom(integrations: Map<String, BackupIntegration>): List<BackupEntry> {
		val out = mutableListOf<BackupEntry>()
		fun add(store: String, key: String, value: Any) {
			entry(store, key, value)?.let(out::add)
		}
		fun link(name: String, store: String, key: String, secretField: String) {
			val item = integrations[name] ?: return
			val address = item.address ?: return
			val secret = item.secret ?: return
			add(secretStore(store), key, buildJsonObject { put("address", address); put(secretField, secret) }.toString())
		}
		integrations[Names.SEERR]?.let { seerr ->
			val address = seerr.address ?: return@let
			add(SETTINGS, "marquee.seerr.address", address)
			seerr.options["pulsarr"]?.let { it.toBooleanStrictOrNull()?.let { on -> add(SETTINGS, "marquee.seerr.pulsarr", on) } }
			seerr.secret?.let { add(secretStore("seerr"), "marquee.seerr.key", it) }
		}
		link(Names.RADARR, "arr_services", "radarr", "apiKey")
		link(Names.SONARR, "arr_services", "sonarr", "apiKey")
		link(Names.BAZARR, "subtitle_services", "bazarr", "apiKey")
		link(Names.HOME_LABBER, "homelabber", "homelabber", "token")
		integrations[Names.SPEECH_DOCK]?.address?.let { add(SETTINGS, "subtitles.speechDock", it) }
		integrations[Names.DIAGNOSTICS]?.let { diagnostics ->
			val server = diagnostics.address ?: return@let
			add(secretStore("diagnostics"), "ntfy.server", server)
			diagnostics.options["topic"]?.let { add(secretStore("diagnostics"), "ntfy.topic", it) }
			diagnostics.secret?.let { add(secretStore("diagnostics"), "ntfy.token", it) }
		}
		return out
	}

	/** Core's `BackupIntegrationName`. */
	private object Names {
		const val SEERR = "seerr"
		const val RADARR = "radarr"
		const val SONARR = "sonarr"
		const val BAZARR = "bazarr"
		const val SPEECH_DOCK = "speechDock"
		const val HOME_LABBER = "homeLabber"
		const val DIAGNOSTICS = "diagnostics"
	}

	private const val DIAGNOSTICS_ITEM = "Diagnostic Logs"

	private fun parseObject(text: String): JsonObject? =
		runCatching { BackupJson.parseToJsonElement(text).jsonObject }.getOrNull()

	private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

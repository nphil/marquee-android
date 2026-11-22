package com.nphil.marquee.session

import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.data.SecretStore
import kotlinx.serialization.Serializable

/** Credentials and their owner are one encrypted record: restore observes either side of a profile switch. */
@Serializable
internal class SavedPlexSignIn(
	val token: String,
	val servers: List<PlexServer> = emptyList(),
	val profile: PlexHomeUser? = null,
) {
	fun save(secrets: SecretStore) {
		secrets.write(KEY, CoreJson.encodeToString(serializer(), this), replaces = legacyKeys)
	}

	companion object {
		private const val KEY = "plex.signIn"
		private const val LEGACY_TOKEN = "plex.token"
		private val legacyKeys = listOf(LEGACY_TOKEN, "plex.servers")

		fun load(secrets: SecretStore): SavedPlexSignIn? {
			val saved = secrets.read(KEY)
			if (saved != null) {
				return runCatching { CoreJson.decodeFromString<SavedPlexSignIn>(saved) }.getOrNull()?.takeIf { it.token.isNotBlank() }
			}
			// The old profile and server tokens were written separately. Only the account token can be carried over.
			return secrets.read(LEGACY_TOKEN)?.takeIf(String::isNotBlank)?.let { SavedPlexSignIn(it) }
		}
	}
}

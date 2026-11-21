package com.nphil.marquee.diagnostics

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nphil.marquee.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The ntfy address, topic and token, and how the log is sent. */
data class DiagConfig(
	val server: String = "",
	val topic: String = "",
	val token: String = "",
	/** The name on the messages; empty means the default one, from the profile. */
	val name: String = "",
	val live: Boolean = true,
	val detailed: Boolean = false,
	val button: Boolean = false,
) {
	/** A saved server and topic are what turn sending on. */
	val configured: Boolean get() = server.isNotBlank() && topic.isNotBlank()
}

/**
 * Where [DiagConfig] lives: the address, topic and token in a Keystore-sealed file of their own, the rest in
 * a preferences file. Separate from the app's own secrets, so forgetting one never touches the other.
 */
class DiagSettings(context: Context) {
	private val secrets = SecretStore(context, "diagnostics")
	private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("diagnostics") }
	private val writing = Mutex()
	private val mutable = MutableStateFlow<DiagConfig?>(null)

	/** Null until [load] has read it. */
	val config: StateFlow<DiagConfig?> = mutable.asStateFlow()

	suspend fun load(): DiagConfig = writing.withLock { mutable.value ?: read().also { mutable.value = it } }

	suspend fun update(change: (DiagConfig) -> DiagConfig): DiagConfig = writing.withLock {
		val old = mutable.value ?: read()
		val new = change(old)
		if (new != old) write(new)
		mutable.value = new
		new
	}

	private suspend fun read(): DiagConfig = withContext(Dispatchers.IO) {
		val prefs = store.data.first()
		DiagConfig(
			server = secrets.read(SERVER).orEmpty(),
			topic = secrets.read(TOPIC).orEmpty(),
			token = secrets.read(TOKEN).orEmpty(),
			name = prefs[NAME].orEmpty(),
			live = prefs[LIVE] ?: true,
			detailed = prefs[DETAILED] ?: false,
			button = prefs[BUTTON] ?: false,
		)
	}

	private suspend fun write(config: DiagConfig) = withContext(Dispatchers.IO) {
		secrets.write(SERVER, config.server.ifBlank { null })
		secrets.write(TOPIC, config.topic.ifBlank { null })
		secrets.write(TOKEN, config.token.ifBlank { null })
		store.edit { prefs ->
			prefs[NAME] = config.name
			prefs[LIVE] = config.live
			prefs[DETAILED] = config.detailed
			prefs[BUTTON] = config.button
		}
	}

	private companion object {
		const val SERVER = "ntfy.server"
		const val TOPIC = "ntfy.topic"
		const val TOKEN = "ntfy.token"
		val NAME = stringPreferencesKey("name")
		val LIVE = booleanPreferencesKey("live")
		val DETAILED = booleanPreferencesKey("detailed")
		val BUTTON = booleanPreferencesKey("button")
	}
}

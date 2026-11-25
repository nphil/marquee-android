package com.nphil.marquee.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nphil.marquee.data.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Android's [BackupStore]: the two preference files and the remote's preferences, and the sealed secrets of the
 * integrations. The preference files are the app's own instances, because a second DataStore on a file the app
 * holds open is refused. Calls block, so run them off the main thread.
 */
internal class AndroidBackupStore(
	private val settings: DataStore<Preferences>,
	private val diagnostics: DataStore<Preferences>,
	private val remote: SharedPreferences,
	private val context: Context,
) : BackupStore {
	private fun secrets(name: String) = SecretStore(context, name)

	/**
	 * SecretStore saves in the background, and a restore ends the process right after: a plain commit writes what
	 * the store has put in memory before this returns.
	 */
	private fun sealed(name: String, change: SecretStore.() -> Unit) {
		secrets(name).change()
		check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()) { "The sealed values were not written." }
	}

	override fun read(): List<BackupEntry> {
		val out = mutableListOf<BackupEntry>()
		runBlocking { settings.data.first() }.asMap().forEach { (key, value) ->
			BackupScope.entry(BackupScope.SETTINGS, key.name, value)?.let(out::add)
		}
		runBlocking { diagnostics.data.first() }.asMap().forEach { (key, value) ->
			BackupScope.entry(BackupScope.DIAGNOSTICS, key.name, value)?.let(out::add)
		}
		remote.all.forEach { (key, value) ->
			if (value != null) BackupScope.entry(BackupScope.REMOTE, key, value)?.let(out::add)
		}
		for ((store, key) in BackupScope.secretKeys) {
			val value = secrets(BackupScope.secretName(store) ?: continue).read(key) ?: continue
			BackupScope.entry(store, key, value)?.let(out::add)
		}
		return out
	}

	override fun validate(entry: BackupEntry): Boolean {
		if (!BackupScope.covers(entry)) return false
		val typed = when (entry.kind) {
			BackupEntry.BOOL -> entry.bool() != null
			BackupEntry.INT -> entry.int() != null
			BackupEntry.DOUBLE -> entry.double()?.let { it.isFinite() && it.toFloat().isFinite() } == true
			BackupEntry.STRING -> entry.string() != null
			BackupEntry.STRINGS -> entry.strings() != null
			else -> false
		}
		if (!typed) return false
		val secret = BackupScope.secretName(entry.store)
		if (secret != null) {
			// Sealed values are text; the connections and pairings are JSON, which the app reads back with the same keys.
			val text = entry.string() ?: return false
			val isLink = entry.key in LINKS
			return !isLink || runCatching { BackupJson.parseToJsonElement(text) }.isSuccess
		}
		return true
	}

	override fun write(entry: BackupEntry) {
		val secret = BackupScope.secretName(entry.store)
		when {
			secret != null -> sealed(secret) { write(entry.key, entry.string()) }
			entry.store == BackupScope.REMOTE -> {
				val edit = remote.edit()
				when (entry.kind) {
					BackupEntry.BOOL -> edit.putBoolean(entry.key, entry.bool()!!)
					BackupEntry.INT -> edit.putInt(entry.key, entry.int()!!)
					BackupEntry.DOUBLE -> edit.putFloat(entry.key, entry.double()!!.toFloat())
					BackupEntry.STRING -> edit.putString(entry.key, entry.string()!!)
					BackupEntry.STRINGS -> edit.putStringSet(entry.key, entry.strings()!!)
				}
				check(edit.commit()) { "The remote's preferences were not written." }
			}
			else -> runBlocking { preferences(entry.store).edit { it.put(entry) } }
		}
	}

	override fun remove(entry: BackupEntry) {
		val secret = BackupScope.secretName(entry.store)
		when {
			secret != null -> sealed(secret) { write(entry.key, null) }
			entry.store == BackupScope.REMOTE -> check(remote.edit().remove(entry.key).commit()) { "The remote's preferences were not written." }
			// Removing by name drops the value whatever its type was.
			else -> runBlocking { preferences(entry.store).edit { it.remove(stringPreferencesKey(entry.key)) } }
		}
	}

	private fun preferences(store: String) = if (store == BackupScope.DIAGNOSTICS) diagnostics else settings

	private fun MutablePreferences.put(entry: BackupEntry) {
		when (entry.kind) {
			BackupEntry.BOOL -> this[booleanPreferencesKey(entry.key)] = entry.bool()!!
			BackupEntry.INT -> this[intPreferencesKey(entry.key)] = entry.int()!!
			BackupEntry.DOUBLE -> this[floatPreferencesKey(entry.key)] = entry.double()!!.toFloat()
			BackupEntry.STRING -> this[stringPreferencesKey(entry.key)] = entry.string()!!
			BackupEntry.STRINGS -> this[stringSetPreferencesKey(entry.key)] = entry.strings()!!
		}
	}

	private companion object {
		val LINKS = setOf("bazarr", "homelabber", "radarr", "sonarr", "pairings")
	}
}

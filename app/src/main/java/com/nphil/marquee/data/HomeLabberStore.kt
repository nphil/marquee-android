package com.nphil.marquee.data

import com.nphil.marquee.core.CoreJson
import com.nphil.marquee.core.HomeLabberLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The HomeLabber Quality Map plugin: off until an address and token are saved, which is what turns it on. The pair
 * belongs to this installation, not to the signed-in Plex profile, and signing out leaves it.
 */
internal class HomeLabberStore(private val secrets: SecretStore, scope: CoroutineScope) {
	private val saved = MutableStateFlow<HomeLabberLink?>(null)
	private val writing = Mutex()
	private val loading = scope.async(Dispatchers.IO) {
		saved.value = secrets.read(KEY)?.let { encoded ->
			runCatching { CoreJson.decodeFromString(HomeLabberLink.serializer(), encoded) }.getOrNull()
		}
	}

	val link = saved.asStateFlow()

	suspend fun current(): HomeLabberLink? {
		loading.await()
		return saved.value
	}

	suspend fun save(link: HomeLabberLink?) = writing.withLock {
		if (current() != link) {
			withContext(Dispatchers.IO) {
				secrets.write(KEY, link?.let { CoreJson.encodeToString(HomeLabberLink.serializer(), it) })
				saved.value = link
			}
		}
	}

	private companion object {
		const val KEY = "homelabber"
	}
}

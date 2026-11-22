package com.nphil.marquee.data

import com.nphil.marquee.core.BazarrLink
import com.nphil.marquee.core.CoreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Subtitle services belong to this installation, not to the signed-in Plex profile. */
internal class SubtitleServices(settings: AppSettings, private val secrets: SecretStore, scope: CoroutineScope) {
	private val savedBazarr = MutableStateFlow<BazarrLink?>(null)
	private val writing = Mutex()
	private val loading = scope.async(Dispatchers.IO) {
		savedBazarr.value = secrets.read(BAZARR)?.let { encoded ->
			runCatching { CoreJson.decodeFromString(BazarrLink.serializer(), encoded) }.getOrNull()
		}
	}

	val bazarr = savedBazarr.asStateFlow()
	val speechDock = settings.speechDockAddress

	suspend fun bazarrLink(): BazarrLink? {
		loading.await()
		return bazarr.value
	}

	suspend fun saveBazarr(link: BazarrLink?) = writing.withLock {
		if (bazarrLink() != link) {
			withContext(Dispatchers.IO) {
				secrets.write(BAZARR, link?.let { CoreJson.encodeToString(BazarrLink.serializer(), it) })
				savedBazarr.value = link
			}
		}
	}

	private companion object {
		const val BAZARR = "bazarr"
	}
}

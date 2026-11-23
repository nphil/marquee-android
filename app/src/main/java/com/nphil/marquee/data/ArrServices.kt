package com.nphil.marquee.data

import com.nphil.marquee.core.ArrKind
import com.nphil.marquee.core.ArrLink
import com.nphil.marquee.core.CoreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Radarr and Sonarr connections belong to this installation, not to the signed-in Plex profile. */
internal class ArrServices(private val secrets: SecretStore, scope: CoroutineScope) {
	private val links = ArrKind.entries.associateWith { MutableStateFlow<ArrLink?>(null) }
	private val writing = Mutex()
	private val loading = scope.async(Dispatchers.IO) {
		for ((kind, flow) in links) {
			flow.value = secrets.read(kind.wire)?.let { encoded ->
				runCatching { CoreJson.decodeFromString(ArrLink.serializer(), encoded) }.getOrNull()
			}
		}
	}

	fun saved(kind: ArrKind): StateFlow<ArrLink?> = links.getValue(kind).asStateFlow()

	suspend fun link(kind: ArrKind): ArrLink? {
		loading.await()
		return saved(kind).value
	}

	suspend fun save(kind: ArrKind, link: ArrLink?) = writing.withLock {
		if (link(kind) != link) {
			withContext(Dispatchers.IO) {
				secrets.write(kind.wire, link?.let { CoreJson.encodeToString(ArrLink.serializer(), it) })
				links.getValue(kind).value = link
			}
		}
	}
}

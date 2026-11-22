package com.nphil.marquee.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.LibraryChanges
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.playback.PlayerCapabilities
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ToastCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal data class DownloadOffer(val scope: DownloadScope, val version: Int, val choices: DownloadChoices)

/** The one download queue. WorkManager wakes it; all recoverable state is in [DownloadStore], not in the job. */
internal class Downloads(
	private val context: Context,
	private val core: MarqueeCore,
	private val session: AppSession,
	private val changes: LibraryChanges,
	private val toasts: ToastCenter,
	private val scope: CoroutineScope,
	http: OkHttpClient,
) {
	private val store = DownloadStore(context.noBackupFilesDir.resolve("downloads"))
	private val transfer = DownloadTransfer(core, store, http)
	private val work by lazy { WorkManager.getInstance(context) }
	private val transfers = Mutex()
	@Volatile private var transferring: String? = null
	private val watches = Mutex()
	private val playing = ConcurrentHashMap.newKeySet<String>()

	val preferences = store.preferences
	val needsProfileVerification = session.state.map { it.needsProfileVerification }
		.stateIn(scope, SharingStarted.Eagerly, session.state.value.needsProfileVerification)
	val records = combine(store.state, session.state) { all, session ->
		val key = session.downloadScope
		all.filter { it.scope == key && it.phase != DownloadPhase.Removed }
	}.stateIn(scope, SharingStarted.Eagerly, emptyList())

	init {
		scope.launch {
			store.load()
			var previous: DownloadScope? = null
			session.state.map { it.downloadWorkScope to (it.phase == AppSession.Phase.SignedOut) }.distinctUntilChanged().collect { (current, signedOut) ->
				if (signedOut) {
					work.cancelUniqueWork(TransferWork)
					work.cancelUniqueWork(SyncWork)
					showErrors { transfers.withLock { store.erase() } }
				} else if (current != null) {
					val policy = if (previous == null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
					scheduleTransfer(policy)
					scheduleSync(policy)
				} else if (previous != null) {
					work.cancelUniqueWork(TransferWork)
					work.cancelUniqueWork(SyncWork)
				}
				previous = current
			}
		}
	}

	suspend fun offer(item: PlexItem, version: Int): DownloadOffer {
		val key = currentScope()
		val server = server(key) ?: throw DownloadFailure("The Plex server's credentials are unavailable.")
		val capabilities = withContext(Dispatchers.Default) { PlayerCapabilities.read(context) }
		val choices = core.downloadChoices(server, item.ratingKey, version, preferences.value.quality, capabilities)
		return DownloadOffer(key, version, choices)
	}

	fun enqueue(offer: DownloadOffer, choice: DownloadChoice) = act {
		if (session.state.value.downloadScope != offer.scope || choice.refusal != null) return@act
		if (store.create(offer.scope, offer.choices, offer.version, choice)) {
			scheduleTransfer()
			toasts.show("Added to Downloads")
		}
	}

	fun enqueueEpisodes(item: PlexItem) = act {
		val key = currentScope()
		val server = server(key) ?: throw DownloadFailure("The Plex server's credentials are unavailable.")
		val keys = core.downloadEpisodes(server, item.ratingKey, show = item.type == MediaType.Show)
		val capabilities = withContext(Dispatchers.Default) { PlayerCapabilities.read(context) }
		val quality = preferences.value.quality
		var added = 0
		var missed = 0
		try {
			for (episode in keys) {
				if (session.state.value.downloadScope != key) break
				if (store.state.value.any { it.scope == key && it.phase != DownloadPhase.Removed && it.title.ratingKey == episode }) continue
				try {
					val choices = core.downloadChoices(server, episode, 0, quality, capabilities)
					val picked = choices.options.firstOrNull { it.id == choices.preferred && it.refusal == null }
					if (picked == null) missed++
					else if (session.state.value.downloadScope == key && store.create(key, choices, 0, picked)) added++
				} catch (_: CoreException) {
					missed++
				}
			}
		} finally {
			if (added > 0) scheduleTransfer()
		}
		if (session.state.value.downloadScope == key) {
			toasts.show(when {
				added > 0 && missed > 0 -> "$added added · $missed couldn't be added"
				added > 0 -> "$added added to Downloads"
				missed > 0 -> "No episodes could be added at that quality"
				keys.isEmpty() -> "No episodes to download"
				else -> "Already in Downloads"
			})
		}
	}

	fun pause(entry: DownloadEntry) = act {
		if (!isCurrent(entry)) return@act
		store.update(entry.id) { if (it.phase.active) it.copy(phase = DownloadPhase.Paused) else it }
		scheduleTransfer(ExistingWorkPolicy.REPLACE)
	}

	fun resume(entry: DownloadEntry) = act {
		if (!isCurrent(entry)) return@act
		store.update(entry.id) {
			if (it.phase == DownloadPhase.Paused || it.phase == DownloadPhase.Failed) it.copy(phase = DownloadPhase.Queued, failure = null) else it
		}
		scheduleTransfer()
	}

	fun remove(entry: DownloadEntry) = act {
		if (!isCurrent(entry) || entry.id in playing) return@act
		val removed = store.update(entry.id) { if (it.id in playing) it else it.copy(phase = DownloadPhase.Removed) }
			?.takeIf { it.phase == DownloadPhase.Removed } ?: return@act
		if (entry.id == transferring) {
			scheduleTransfer(ExistingWorkPolicy.REPLACE)
			transfers.withLock { store.discardMedia(removed) }
		} else store.discardMedia(removed)
		val latest = store.get(removed.id) ?: return@act
		if (latest.watch.unsynced == null && latest.conversion == null) store.forget(latest)
		else scheduleSync()
	}

	fun changePreferences(change: (DownloadPreferences) -> DownloadPreferences) = act {
		val before = store.setPreferences(change)
		if (before.metered != preferences.value.metered) scheduleTransfer(ExistingWorkPolicy.REPLACE)
		if (!before.autoDelete && preferences.value.autoDelete) scheduleSync()
	}

	suspend fun usage(): DownloadDisk = store.usage()

	suspend fun acquire(id: String): LocalDownload? {
		playing.add(id)
		var local: LocalDownload? = null
		try {
			val entry = store.get(id)?.takeIf { it.phase == DownloadPhase.Ready && isCurrent(it) } ?: return null
			local = store.playback(entry)?.takeIf { isCurrent(entry) }
			return local
		} finally {
			if (local == null) playing.remove(id)
		}
	}

	fun poster(entry: DownloadEntry) = store.poster(entry)

	fun next(after: DownloadEntry): DownloadEntry? {
		val show = after.title.showKey ?: return null
		val season = after.title.season ?: return null
		val episode = after.title.episode ?: return null
		val candidates = records.value.filter { it.phase == DownloadPhase.Ready && it.title.showKey == show }
		return candidates.firstOrNull { it.title.season == season && it.title.episode == episode + 1 }
			?: candidates.firstOrNull { it.title.season == season + 1 && it.title.episode == 1 }
	}

	/** Called at a pause, periodically while playing, and at the end. File progress is separate from the resume point. */
	fun watched(id: String, offset: Int?, finished: Boolean, left: Boolean = false) {
		val now = System.currentTimeMillis()
		act {
			watches.withLock {
				try {
					val entry = store.get(id)?.takeIf { it.phase == DownloadPhase.Ready } ?: return@withLock
					if (finished || offset != null && offset != entry.watch.offset) {
						val watch = core.updateDownloadWatch(entry.watch, offset ?: 0, finished, now)
						store.update(id) { it.copy(watch = watch) }
						if (entry.watch.unsynced == null) scheduleSync(ExistingWorkPolicy.KEEP)
					}
				} finally {
					if (left) {
						playing.remove(id)
						scheduleSync()
					}
				}
			}
		}
	}

	/** True when the same work needs another attempt. A failed title does not stop the others. */
	suspend fun transferWork(foreground: suspend (DownloadEntry) -> Unit): Boolean {
		val key = awaitScope() ?: return false
		while (true) {
			val next = store.state.value.firstOrNull { it.scope == key && it.phase.active } ?: return false
			val retry = transfers.withLock {
				transferring = next.id
				try {
					val entry = store.get(next.id)?.takeIf { it.phase.active } ?: return@withLock false
					val server = server(key) ?: return false
					foreground(entry)
					transfer.run(entry, server)
					scheduleSync()
					false
				} catch (e: CancellationException) {
					withContext(NonCancellable) { store.update(next.id) { if (it.phase.active) it.copy(phase = DownloadPhase.Queued) else it } }
					throw e
				} catch (_: TransferStopped) {
					false
				} catch (e: Exception) {
					val again = transient(e)
					Diag.warning(DiagCategory.Download, if (again) "transfer.retry" else "transfer.failed", "error" to e.javaClass.simpleName)
					store.update(next.id) {
						if (!it.phase.active) it
						else if (again) it.copy(phase = DownloadPhase.Queued)
						else it.copy(phase = DownloadPhase.Failed, failure = downloadMessage(e))
					}
					again
				} finally {
					transferring = null
				}
			}
			if (retry) return true
		}
	}

	suspend fun syncWork(): Boolean {
		val key = awaitScope() ?: return false
		var pending = false
		for (record in store.state.value) {
			if (record.scope != key || !needsSync(record)) continue
			if (record.id in playing) {
				pending = true
				continue
			}
			val server = server(key) ?: return false
			try {
				if (sync(record, server)) pending = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Diag.warning(DiagCategory.Download, "sync.retry", "error" to e.javaClass.simpleName)
				pending = true
			}
		}
		return pending
	}

	private suspend fun sync(record: DownloadEntry, server: DownloadServer): Boolean {
		var entry = store.get(record.id) ?: return false
		if (!isCurrent(entry)) return false
		if (entry.id in playing) return true
		if (entry.phase == DownloadPhase.Removed && entry.id == transferring) return true
		while (entry.watch.unsynced != null) {
			if (!isCurrent(entry)) return false
			if (entry.id in playing) return true
			val before = entry.watch
			val synced = try {
				core.syncDownloadWatch(server, entry)
			} catch (e: CoreException) {
				// A title removed from Plex has no resume point to synchronize.
				if (e.status != 404) throw e
				SyncedWatch(before.copy(unsynced = null, finishedUnsynced = false, scrobble = null), changedServer = false)
			}
			entry = withContext(NonCancellable) {
				store.update(entry.id) { if (it.watch == before) it.copy(watch = synced.watch) else it }
			} ?: return false
			if (synced.changedServer && isCurrent(entry)) changes.record(entry.title.keys)
			if (entry.watch != synced.watch || before.scrobble != null || synced.watch.scrobble == null) break
		}
		if (!isCurrent(entry)) return false
		if (autoDelete(entry) && entry.id !in playing) {
			entry = store.update(entry.id) { if (it.id !in playing && autoDelete(it)) it.copy(phase = DownloadPhase.Removed) else it }
				?: return false
		}
		if (entry.phase == DownloadPhase.Removed) store.discardMedia(entry)
		val conversion = entry.conversion
		if (conversion != null && (entry.phase == DownloadPhase.Removed || entry.phase == DownloadPhase.Ready)) {
			try {
				core.removeConversion(server, conversion)
			} catch (e: CoreException) {
				if (e.status != 404) throw e
			}
			entry = store.update(entry.id) { it.copy(conversion = null) } ?: return false
		}
		if (entry.phase == DownloadPhase.Removed && entry.watch.unsynced == null) store.forget(entry)
		return entry.watch.unsynced != null
	}

	private fun act(block: suspend () -> Unit) {
		scope.launch { showErrors(block) }
	}

	private suspend fun showErrors(block: suspend () -> Unit) {
		try {
			block()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.warning(DiagCategory.Download, "action.failed", "error" to e.javaClass.simpleName)
			toasts.show(downloadMessage(e))
		}
	}

	private fun isCurrent(entry: DownloadEntry) = session.state.value.downloadScope == entry.scope

	private fun currentScope(): DownloadScope {
		val state = session.state.value
		if (state.needsProfileVerification) throw DownloadFailure("Reconnect to Plex to verify this profile before downloading.")
		return state.downloadScope ?: throw DownloadFailure("Choose a Plex server first.")
	}

	private fun server(key: DownloadScope): DownloadServer? {
		val state = session.state.value
		val chosen = state.downloadServer?.takeUnless { it.accessToken.isNullOrBlank() } ?: return null
		return if (state.downloadScope == key) DownloadServer(chosen, state.addresses[chosen.id]) else null
	}

	private suspend fun awaitScope(): DownloadScope? {
		store.load()
		return session.state.first { it.phase != AppSession.Phase.Restoring }.downloadWorkScope
	}

	private fun scheduleTransfer(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
		val key = session.state.value.downloadWorkScope ?: return
		if (store.state.value.none { it.scope == key && it.phase.active }) {
			if (policy == ExistingWorkPolicy.REPLACE) work.cancelUniqueWork(TransferWork)
			return
		}
		val network = if (preferences.value.metered) NetworkType.CONNECTED else NetworkType.UNMETERED
		work.enqueueUniqueWork(TransferWork, policy, OneTimeWorkRequestBuilder<DownloadWorker>()
			.setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build())
	}

	private fun scheduleSync(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
		val key = session.state.value.downloadWorkScope ?: return
		if (store.state.value.none { it.scope == key && needsSync(it) }) return
		work.enqueueUniqueWork(SyncWork, policy, OneTimeWorkRequestBuilder<DownloadSyncWorker>()
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS).build())
	}

	private fun autoDelete(entry: DownloadEntry) = preferences.value.autoDelete &&
		entry.phase == DownloadPhase.Ready && entry.watch.finishedHere && entry.watch.unsynced == null

	private fun needsSync(entry: DownloadEntry) = entry.watch.unsynced != null || entry.phase == DownloadPhase.Removed ||
		(entry.phase == DownloadPhase.Ready && entry.conversion != null) || autoDelete(entry)

	private companion object {
		const val TransferWork = "marquee.downloads.transfer"
		const val SyncWork = "marquee.downloads.sync"
	}
}

// Profile switches update the server list before the open screens reconnect to the new credentials.
private val AppSession.State.downloadServer get() =
	server?.takeIf { phase == AppSession.Phase.Ready && it.id.isNotBlank() }?.let { chosen -> servers.firstOrNull { it.id == chosen.id } }

internal val AppSession.State.downloadScope: DownloadScope? get() {
	val profile = user?.uuid?.takeIf(String::isNotBlank) ?: return null
	return downloadServer?.let { DownloadScope(it.id, profile, verified = true) }
}

private val AppSession.State.downloadWorkScope: DownloadScope? get() =
	if (downloadServer?.accessToken.isNullOrBlank()) null else downloadScope

private fun retryableStatus(status: Int) = status == 202 || status == 408 || status == 429 || status in 500..599

private fun transient(error: Exception): Boolean = when (error) {
	is CoreException -> error.kind == CoreException.Kind.Unreachable || error.kind == CoreException.Kind.NoConnections ||
		retryableStatus(error.status ?: 0)
	is TransferResponse -> retryableStatus(error.status)
	is IOException -> true
	else -> false
}

internal fun downloadMessage(error: Exception): String = when (error) {
	is DownloadFailure, is StorageFailure -> error.message ?: "Couldn't save the download."
	is TransferResponse -> "The server couldn't send the download (HTTP ${error.status})."
	is CoreException -> when {
		transient(error) -> "Couldn't reach the Plex server."
		error.kind == CoreException.Kind.Unauthorized -> "The server no longer allows this download."
		error.status == 404 -> "The server couldn't find this title or its download queue."
		else -> "The server couldn't prepare this download."
	}
	else -> "Couldn't save the download."
}

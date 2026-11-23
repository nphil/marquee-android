package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.ArrKind
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.DeleteOutcome
import com.nphil.marquee.core.DeleteServices
import com.nphil.marquee.core.Deletion
import com.nphil.marquee.core.LibraryChanges
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.WatchlistCleanup
import com.nphil.marquee.core.cleanUp
import com.nphil.marquee.core.delete
import com.nphil.marquee.data.ArrServices
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.seerr.Seerr
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ToastCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * A title's delete as two steps. The server's answers first, so the title leaves every list and its page closes at once;
 * the Watchlist, Radarr or Sonarr, and Seerr follow, each answering for itself. They run on the app's scope, and what
 * they leave undone is held here rather than by the page that started the delete, which is gone by then.
 */
internal class MediaDeletions(
	private val core: MarqueeCore,
	private val session: AppSession,
	private val changes: LibraryChanges,
	private val toasts: ToastCenter,
	private val seerr: Seerr,
	private val arr: ArrServices,
	private val scope: CoroutineScope,
) {
	/** A cleanup that left something undone: the title, and how each step stands. */
	class Leftover(val item: PlexItem, val result: Deletion) {
		/** Asking again may help: a step failed rather than being refused or unable to match the title. */
		val canRetry: Boolean get() = result.ticket != null
	}

	private val queue = MutableStateFlow<List<Leftover>>(emptyList())

	/** The alert on screen, or the next to be. */
	val current: StateFlow<Leftover?> = queue.map { it.firstOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)

	/**
	 * Deletes [item] on the server. Once it is gone the title leaves every list and the steps after it start in the
	 * background; the answer is the server's alone.
	 */
	suspend fun delete(item: PlexItem): DeleteOutcome {
		val services = services(item)
		val reply = try {
			session.onServer { server, remembered ->
				seerr.whenOn("delete") { link -> core.delete(server, remembered, item, services.withSeerr(link)) }
			}
		} catch (e: CoreException) {
			Diag.warning(DiagCategory.Ui, "detail.delete.failed", "kind" to e.kind.name, "status" to e.status)
			return DeleteOutcome.Failed
		}
		if (reply.outcome != DeleteOutcome.Deleted) return reply.outcome
		changes.recordDeletion(item)
		reply.ticket?.let { ticket -> scope.launch { cleanUp(item, ticket, previous = null) } }
		return DeleteOutcome.Deleted
	}

	fun dismiss(leftover: Leftover) = queue.update { it - leftover }

	/** Repeats the steps that failed. The title is already off the server and out of every list. */
	fun retry(leftover: Leftover) {
		val ticket = leftover.result.ticket ?: return
		dismiss(leftover)
		scope.launch { cleanUp(leftover.item, ticket, previous = leftover.result) }
	}

	/** Only the service that holds this kind of title is named, so the other one's key is not sent. */
	private suspend fun services(item: PlexItem): DeleteServices {
		val kind = DeleteWording.arrKind(item)
		return DeleteServices(
			accountToken = session.accountToken,
			radarr = if (kind == ArrKind.Radarr) arr.link(kind) else null,
			sonarr = if (kind == ArrKind.Sonarr) arr.link(kind) else null,
			seerr = null,
		)
	}

	private suspend fun cleanUp(item: PlexItem, ticket: JsonObject, previous: Deletion?) {
		val services = services(item)
		val result = try {
			session.onServer { server, remembered ->
				seerr.whenOn("cleanUp") { link ->
					core.cleanUp(server, remembered, ticket, changes.state.value.deleted.keys, services.withSeerr(link), previous)
				}
			}
		} catch (e: CoreException) {
			Diag.warning(DiagCategory.Integration, "delete.cleanup.failed", "kind" to e.kind.name, "status" to e.status)
			toasts.show("Deleted “${item.title}”, but couldn't finish removing it from the other services.")
			return
		}
		conclude(item, result)
	}

	/** A toast when no step failed; otherwise the alert says what is left, with Retry where asking again may help. */
	private fun conclude(item: PlexItem, result: Deletion) {
		if (result.watchlist == WatchlistCleanup.Removed) result.guid?.let(changes::hideFromWatchlist)
		val lines = DeleteWording.shortfall(result, item)
		val fields = arrayOf("watchlist" to result.watchlist.name, "arr" to result.arr.name, "requests" to result.requests.name)
		if (lines.isEmpty()) {
			Diag.info(DiagCategory.Integration, "delete.cleanup", *fields)
			toasts.show(DeleteWording.summary(result, item))
		} else {
			Diag.warning(DiagCategory.Integration, "delete.cleanup", *fields)
			queue.update { it + Leftover(item, result) }
		}
	}
}

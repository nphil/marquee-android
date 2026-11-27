package com.nphil.marquee.browse

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.addToWatchlist
import com.nphil.marquee.core.removeFromContinueWatching
import com.nphil.marquee.core.removeFromWatchlist
import com.nphil.marquee.core.setWatched
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Watched and Continue Watching changes as DESIGN §8.3 and §11 describe them: the screen shows the change at once; once
 * the server confirms, a message offers Undo; a refused change rolls back. Every call here outlives the screen that
 * made it, so a change is never half sent because a person left.
 */
class ItemActions(private val env: BrowseEnvironment) {
	/** What a watched change covers; it words the message. Plex marks every episode under a show or season. */
	enum class Scope {
		Title, Season, Show;

		fun marked(watched: Boolean): String {
			val state = if (watched) "watched" else "unwatched"
			return when (this) {
				Title -> "Marked $state"
				Season -> "Season marked $state"
				Show -> "Show marked $state"
			}
		}

		fun failed(watched: Boolean): String {
			val state = if (watched) "watched" else "unwatched"
			return when (this) {
				Title -> "Couldn't mark $state"
				Season -> "Couldn't mark season $state"
				Show -> "Couldn't mark show $state"
			}
		}
	}

	/**
	 * Marks [item] watched or unwatched. [show] puts a state on screen; Undo and a failure call it again with the state
	 * to return to. A show or season passes its own rating key, and Plex marks every episode under it.
	 */
	fun setWatched(watched: Boolean, item: PlexItem, reversible: Boolean = true, show: (Boolean) -> Unit) {
		val scope = when (item.type) {
			MediaType.Show -> Scope.Show
			MediaType.Season -> Scope.Season
			else -> Scope.Title
		}
		setWatched(watched, item.ratingKey, listOf(item.parentRatingKey, item.grandparentRatingKey), scope, reversible, show)
	}

	/** The same for the title with rating key [key], whose parents [touching] names. A screen that shows an episode and offers its show passes the show's key. */
	fun setWatched(watched: Boolean, key: String, touching: List<String?>, scope: Scope, reversible: Boolean = true, show: (Boolean) -> Unit) {
		val touched = (listOf(key) + touching).filterNotNull().toSet()

		fun send(watched: Boolean, onSuccess: () -> Unit, onFailure: () -> Unit) {
			env.scope.launch {
				try {
					env.source.onServer { server, remembered -> this.setWatched(server, remembered, key, watched) }
					env.changes.record(touched)
					onSuccess()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Diag.warning(DiagCategory.Plex, "watched.failed", "watched" to watched, "error" to e.javaClass.simpleName)
					onFailure()
				}
			}
		}

		show(watched)
		send(
			watched,
			onSuccess = {
				env.toasts.show(
					scope.marked(watched),
					undo = if (!reversible) null else ({
						show(!watched)
						send(
							!watched,
							onSuccess = {},
							onFailure = {
								show(watched)
								env.toasts.show("Couldn't undo")
							},
						)
					}),
				)
			},
			onFailure = {
				show(!watched)
				env.toasts.show(scope.failed(watched))
			},
		)
	}

	/**
	 * Takes [item] off Continue Watching. [hide] hides it at once; the server hears of it only once the message has gone
	 * without Undo (or the app stops), so Undo never needs a call of its own. The card stays out of Home's refetches until
	 * the server answers; a refused call puts it back.
	 */
	fun removeFromContinueWatching(item: PlexItem, hide: (Boolean) -> Unit) {
		val key = item.ratingKey
		val changes = env.changes
		changes.startRemovingFromContinueWatching(key)
		hide(true)
		env.toasts.show(
			"Removed from Continue Watching",
			undo = {
				changes.endRemovingFromContinueWatching(key)
				hide(false)
			},
			commit = {
				env.scope.launch {
					try {
						env.source.onServer { server, remembered -> this.removeFromContinueWatching(server, remembered, key) }
						changes.endRemovingFromContinueWatching(key)
						changes.record(setOf(key))
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Diag.warning(DiagCategory.Plex, "continue.remove.failed", "error" to e.javaClass.simpleName)
						changes.endRemovingFromContinueWatching(key)
						hide(false)
						env.toasts.show("Couldn't remove from Continue Watching")
					}
				}
			},
		)
	}

	/**
	 * Puts the catalogue title [guid] on the watchlist or takes it off. [show] puts the state on screen ahead of the server;
	 * Undo and a failure call it again with the state to return to.
	 */
	fun setWatchlisted(on: Boolean, guid: String, show: (Boolean) -> Unit) {
		fun send(on: Boolean, onSuccess: () -> Unit, onFailure: () -> Unit) {
			env.scope.launch {
				try {
					val sent = env.source.onAccount { token -> if (on) addToWatchlist(token, guid) else removeFromWatchlist(token, guid) }
					if (sent == null) {
						onFailure()
						return@launch
					}
					if (on) env.changes.showInWatchlist(guid)
					env.changes.recordWatchlist()
					onSuccess()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Diag.warning(DiagCategory.Plex, "watchlist.failed", "on" to on, "error" to e.javaClass.simpleName)
					onFailure()
				}
			}
		}

		show(on)
		send(
			on,
			onSuccess = {
				env.toasts.show(
					if (on) "Added to Watchlist" else "Removed from Watchlist",
					undo = {
						show(!on)
						send(
							!on,
							onSuccess = {},
							onFailure = {
								show(on)
								env.toasts.show("Couldn't undo")
							},
						)
					},
				)
			},
			onFailure = {
				show(!on)
				env.toasts.show("Couldn't update the Watchlist")
			},
		)
	}
}

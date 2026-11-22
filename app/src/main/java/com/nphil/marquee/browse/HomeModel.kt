package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.Changes
import com.nphil.marquee.core.HomeLayout
import com.nphil.marquee.core.HomeLoad
import com.nphil.marquee.core.HomeReply
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.Page
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SnapshotMode
import com.nphil.marquee.core.dismissRewatch
import com.nphil.marquee.core.followHome
import com.nphil.marquee.core.homeRows
import com.nphil.marquee.core.restoreRewatch
import com.nphil.marquee.core.watchlistPage
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What Home draws now. */
@Immutable
data class HomeUi(
	val remote: Remote<HomeReply> = Remote(),
	/** The rows to draw: switched on by the layout, titles deleted this session left out, empty rows dropped. */
	val rows: List<HomeRow> = emptyList(),
	/** Every row is switched off while some hold titles, so Home says so rather than showing nothing. */
	val allHidden: Boolean = false,
	/** The Watchlist's first titles, while it shows on Home as a shelf. */
	val watchlist: List<PlexItem> = emptyList(),
	/** The Watchlist holds more than the shelf shows. */
	val watchlistMore: Boolean = false,
	/** A pull to refresh is under way; a refresh that changes are behind shows no spinner. */
	val pulling: Boolean = false,
)

/**
 * Home (DESIGN §8.1): the rows the profile chose in Home Rows, in its order. The last Home saved for this server and
 * profile shows while the server is asked again; the answer replaces it in one step and is saved for the next launch.
 * Continue Watching follows the shows watched again: their cards are read beside Plex's own answer and put into the row
 * when they are ready within a moment, or into it after it has shown, so the row is not held up.
 */
class HomeModel(private val env: BrowseEnvironment, private val place: BrowsePlace) : ViewModel() {
	private val loader = RemoteLoader<HomeReply>("home")
	private val actions = ItemActions(env)

	private val attempt = MutableStateFlow(0)
	private val pulling = MutableStateFlow(false)
	private val watchlistShelf = MutableStateFlow(false)
	private val watchlist = MutableStateFlow<Page<PlexItem>?>(null)

	/** The profile's row layout; null until the saved one is read, so Home never loads with the default and then again with its own. */
	val layout: StateFlow<HomeLayout?> =
		env.preferences.homeLayout(place.profile).stateIn(viewModelScope, SharingStarted.Eagerly, null)

	val ui: StateFlow<HomeUi> = combine(loader.state, layout, env.changes.state, watchlist, pulling) { remote, layout, changes, saved, pull ->
		val arranged = layout.arrangedRows(remote.value, changes)
		val rows = arranged.filter { it.shown && it.hub.items.isNotEmpty() }
		val titles = saved?.let { changes.shownInWatchlist(it.items) }.orEmpty()
		HomeUi(
			remote = remote,
			rows = rows,
			allHidden = rows.isEmpty() && arranged.any { it.hub.items.isNotEmpty() },
			watchlist = titles,
			watchlistMore = saved != null && saved.total > saved.items.size,
			pulling = pull,
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

	init {
		// A library's hubs are asked for only while one of its rows is shown, so showing one reloads. So does switching a
		// library on or off in Favorite Libraries.
		viewModelScope.launch {
			combine(
				layout.filterNotNull().map { it.shownLibraries }.distinctUntilChanged(),
				env.preferences.hiddenLibraries(place.profile).distinctUntilChanged(),
				env.changes.state.map { it.revision }.distinctUntilChanged(),
				attempt,
			) { libraries, hidden, _, _ -> libraries to hidden }.collectLatest { (libraries, hidden) ->
				load(libraries, hidden)
				pulling.value = false
			}
		}
		// Each profile has its own watchlist, reached with its own token; it is asked for only while the shelf shows.
		viewModelScope.launch {
			combine(watchlistShelf, env.changes.state.map { it.watchlistRevision }.distinctUntilChanged()) { shelf, _ -> shelf }
				.collectLatest { shelf -> if (shelf) loadWatchlist() else watchlist.value = null }
		}
	}

	/** Whether Home shows the Watchlist as a shelf: while Watchlist is not a tab. */
	fun showWatchlistShelf(on: Boolean) {
		watchlistShelf.value = on
	}

	/** A pull to refresh. */
	fun refresh() {
		pulling.value = true
		attempt.update { it + 1 }
	}

	fun retry() {
		attempt.update { it + 1 }
	}

	/** Hides a row from its title's long press, with Undo in the message. */
	fun hide(row: HomeRow) {
		val before = layout.value ?: return
		val reply = loader.state.value.value ?: return
		val arranged = before.arrange(reply.rows)
		val index = arranged.indexOfFirst { it.id == row.id }
		if (index < 0) return
		val edited = arranged.toMutableList().also { it[index] = it[index].copy(shown = false) }
		save(before.saving(edited))
		env.toasts.show("${row.title} hidden", undo = { save(before) })
	}

	/** Marks a Continue Watching card watched: it leaves the row at once. */
	fun markWatched(item: PlexItem, index: Int) =
		actions.setWatched(true, item) { nowWatched -> setShown(!nowWatched, item, index) }

	/** Marks the show of an episode card watched; every card of the show leaves at once, as the server drops its Continue Watching entries. */
	fun markShowWatched(show: String, cards: List<IndexedValue<PlexItem>>) =
		actions.setWatched(true, show, touching = emptyList(), scope = ItemActions.Scope.Show) { nowWatched ->
			for (card in cards) setShown(!nowWatched, card.value, card.index)
		}

	/** Marks a show on a poster shelf watched or unwatched; the shelf shows it once the server has answered. */
	fun setWatched(watched: Boolean, item: PlexItem) = actions.setWatched(watched, item) {}

	fun removeFromContinueWatching(item: PlexItem, index: Int) =
		actions.removeFromContinueWatching(item) { hidden -> setShown(!hidden, item, index) }

	/** Takes a title off the Watchlist, with Undo in the message; the poster goes at once. */
	fun removeFromWatchlist(item: PlexItem) {
		val guid = item.guid ?: return
		actions.setWatchlisted(false, guid) { on -> if (on) env.changes.showInWatchlist(guid) else env.changes.hideFromWatchlist(guid) }
	}

	/**
	 * Takes a next-episode card off Continue Watching, and with it the older entry of Plex's it replaced, until its show is
	 * viewed again; Undo in the message brings it back.
	 */
	fun dismissRewatch(item: PlexItem, index: Int) {
		val show = item.grandparentRatingKey ?: return
		if (item.rewatchedAt == null) return
		setShown(false, item, index)
		env.scope.launch {
			try {
				val before = env.preferences.rewatchDismissals(place.profile) ?: JsonObject(emptyMap())
				val after = env.source.onServer { server, remembered -> this.dismissRewatch(server, remembered, item, before) }
				env.preferences.setRewatchDismissals(place.profile, after)
				Diag.info(DiagCategory.Plex, "rewatch.dismiss", "show" to show)
				env.toasts.show(
					"Removed from Continue Watching",
					undo = {
						env.scope.launch { restore(item, index, show) }
					},
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Diag.warning(DiagCategory.Plex, "rewatch.dismiss.failed", "error" to e.javaClass.simpleName)
				setShown(true, item, index)
				env.toasts.show("Couldn't remove from Continue Watching")
			}
		}
	}

	private suspend fun restore(item: PlexItem, index: Int, show: String) {
		try {
			val before = env.preferences.rewatchDismissals(place.profile) ?: JsonObject(emptyMap())
			val after = env.source.onServer { server, remembered -> this.restoreRewatch(server, remembered, item, before) }
			env.preferences.setRewatchDismissals(place.profile, after)
			Diag.info(DiagCategory.Plex, "rewatch.restore", "show" to show)
			setShown(true, item, index)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.warning(DiagCategory.Plex, "rewatch.restore.failed", "error" to e.javaClass.simpleName)
			env.toasts.show("Couldn't undo")
		}
	}

	/** Takes a card out of Continue Watching, or puts it back where it was, ahead of the server. */
	private fun setShown(shown: Boolean, item: PlexItem, index: Int) = loader.update { reply ->
		reply.copy(
			rows = reply.rows.map { row ->
				if (!row.continueWatching) return@map row
				val items = row.hub.items.filter { it.ratingKey != item.ratingKey }.toMutableList()
				if (shown) items.add(index.coerceIn(0, items.size), item)
				row.copy(hub = row.hub.copy(items = items))
			},
		)
	}

	private fun save(layout: HomeLayout) {
		env.scope.launch { env.preferences.setHomeLayout(place.profile, layout) }
	}

	private suspend fun load(libraries: Set<String>, hidden: Set<String>) {
		val dismissed = env.preferences.rewatchDismissals(place.profile)
		val snapshot = place.snapshot(env.snapshots)
		fun request(mode: SnapshotMode, showing: Boolean) =
			HomeLoad(libraries.toList(), hidden.toList(), dismissed, mode, snapshot, showing)

		loader.load(
			cached = {
				val reply = env.source.onServer { server, remembered ->
					homeRows(server, remembered, request(SnapshotMode.Saved, showing = false))
				}
				reply.savedAt?.let { Saved(reply, (it * 1000).toLong()) }
			},
			fetch = {
				val showing = loader.state.value.value != null
				env.source.onServer { server, remembered -> homeRows(server, remembered, request(SnapshotMode.Live, showing)) }
			},
		)
		val state = loader.state.value
		val reply = state.value
		val run = reply?.run
		if (state.failure != null || reply == null || !reply.late || run == null) return
		val followed = try {
			env.source.onServer { server, remembered -> followHome(server, remembered, run, dismissed) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.notice(DiagCategory.Plex, "home.follow.failed", "error" to e.javaClass.simpleName)
			null
		}
		if (followed != null) loader.update { it.copy(rows = followed, late = false) }
	}

	/** The shelf shows the last saved page at once, as the iPhone's does, and the live answer replaces it and records the next. */
	private suspend fun loadWatchlist() {
		val snapshot = place.snapshot(env.snapshots)
		if (watchlist.value == null) {
			val saved = try {
				env.source.onAccount { token -> watchlistPage(token, 0, WATCHLIST_SHELF, snapshot, SnapshotMode.Saved) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Nothing is saved on a first launch or after a profile switch.
				null
			}
			if (saved != null && watchlist.value == null) watchlist.value = saved
		}
		val page = try {
			env.source.onAccount { token -> watchlistPage(token, 0, WATCHLIST_SHELF, snapshot, SnapshotMode.Live) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.notice(DiagCategory.Plex, "home.watchlist.failed", "error" to e.javaClass.simpleName)
			null
		}
		if (page != null) watchlist.value = page
	}

	private companion object {
		const val WATCHLIST_SHELF = 20
	}
}

/** The rows of [reply] as [layout] arranges them, without the titles deleted this session and the cards being removed. */
private fun HomeLayout?.arrangedRows(reply: HomeReply?, changes: Changes): List<HomeRow> {
	if (this == null || reply == null) return emptyList()
	return arrange(reply.rows).map { row ->
		val items = changes.shown(row.hub.items)
			.let { if (row.continueWatching) changes.shownInContinueWatching(it) else it }
			// A lazy row breaks on two items of one key.
			.distinctBy { it.ratingKey }
		if (items.size == row.hub.items.size) row else row.copy(hub = row.hub.copy(items = items))
	}
}

package com.nphil.marquee.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.DeleteOutcome
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexHub
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.ServerInfo
import com.nphil.marquee.core.Watchlist
import com.nphil.marquee.core.addToWatchlist
import com.nphil.marquee.core.children
import com.nphil.marquee.core.deleteVersion
import com.nphil.marquee.core.extras
import com.nphil.marquee.core.item
import com.nphil.marquee.core.onWatchlist
import com.nphil.marquee.core.related
import com.nphil.marquee.core.removeFromWatchlist
import com.nphil.marquee.core.serverInfo
import com.nphil.marquee.core.setWatched
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.ui.Detail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Everything a title's page shows, which arrives in pieces: the page draws what it has. */
@Immutable
data class DetailState(
	val item: PlexItem? = null,
	val loading: Boolean = true,
	/** Why the page could not load at all; null while loading or once it has. */
	val failure: CoreException.Kind? = null,
	/** Whether the server has this title: false for a Watchlist title it lacks. */
	val onServer: Boolean = true,
	val extras: List<PlexItem> = emptyList(),
	val related: List<PlexHub> = emptyList(),
	/** A show's seasons. */
	val seasons: List<PlexItem> = emptyList(),
	/** The season whose episodes are listed. */
	val seasonKey: String? = null,
	/** The episodes of [seasonKey]; null until they arrive. */
	val episodes: List<PlexItem>? = null,
	val episodesFailed: Boolean = false,
	val info: ServerInfo = ServerInfo(),
	/** Whether the title is on the Watchlist; null where it cannot be (no account, or no catalogue entry). */
	val watchlisted: Boolean? = null,
) {
	val season: PlexItem? get() = seasons.firstOrNull { it.ratingKey == seasonKey }

	/** The page's own item, one of its seasons or one of the listed episodes. */
	fun find(ratingKey: String): PlexItem? =
		item?.takeIf { it.ratingKey == ratingKey } ?: seasons.firstOrNull { it.ratingKey == ratingKey }
			?: episodes?.firstOrNull { it.ratingKey == ratingKey }
}

/**
 * What the screen shows with [ratingKey] marked, as it will be once the server confirms: a show or season takes
 * its seasons and episodes with it, and an episode moves the counts of its season and show.
 */
internal fun DetailState.withWatched(ratingKey: String, watched: Boolean): DetailState {
	val page = item
	if (page != null && page.ratingKey == ratingKey) {
		return if (page.type == MediaType.Show || page.type == MediaType.Season) {
			copy(item = page.marked(watched), seasons = seasons.map { it.marked(watched) }, episodes = episodes?.map { it.marked(watched) })
		} else {
			copy(item = page.marked(watched))
		}
	}
	if (seasons.any { it.ratingKey == ratingKey }) {
		val marked = seasons.map { if (it.ratingKey == ratingKey) it.marked(watched) else it }
		val listed = if (seasonKey == ratingKey) episodes?.map { it.marked(watched) } else episodes
		return copy(seasons = marked, episodes = listed, item = page?.recountedFrom(marked))
	}
	val listed = episodes?.map { if (it.ratingKey == ratingKey) it.marked(watched) else it } ?: return this
	if (page?.type == MediaType.Season) return copy(episodes = listed, item = page.recountedFromEpisodes(listed))
	val seasonsNow = seasons.map { if (it.ratingKey == seasonKey) it.recountedFromEpisodes(listed) else it }
	return copy(episodes = listed, seasons = seasonsNow, item = page?.recountedFrom(seasonsNow))
}

private fun PlexItem.marked(watched: Boolean): PlexItem = when (type) {
	MediaType.Show, MediaType.Season -> copy(
		isWatched = watched,
		unwatchedLeafCount = if (watched) 0 else leafCount ?: unwatchedLeafCount,
		viewedLeafCount = if (watched) leafCount ?: viewedLeafCount else 0,
	)
	else -> copy(isWatched = watched, viewOffset = 0, viewCount = if (watched) max(viewCount, 1) else 0)
}

private fun PlexItem.recountedFromEpisodes(episodes: List<PlexItem>): PlexItem {
	val unwatched = episodes.count { !it.isWatched }
	return copy(isWatched = unwatched == 0, unwatchedLeafCount = unwatched, viewedLeafCount = (leafCount ?: episodes.size) - unwatched)
}

private fun PlexItem.recountedFrom(seasons: List<PlexItem>): PlexItem {
	if (type != MediaType.Show || seasons.any { it.unwatchedLeafCount == null }) return this
	val unwatched = seasons.sumOf { it.unwatchedLeafCount ?: 0 }
	return copy(isWatched = unwatched == 0, unwatchedLeafCount = unwatched, viewedLeafCount = (leafCount ?: 0) - unwatched)
}

/** The season to show first (iPhone's rule): the one with the episode up next, else the first with episodes left. */
internal fun firstSeason(seasons: List<PlexItem>, onDeck: PlexItem?): PlexItem? {
	onDeck?.parentRatingKey?.let { key -> seasons.firstOrNull { it.ratingKey == key }?.let { return it } }
	val regular = seasons.filter { (it.index ?: 0) != 0 }
	return regular.firstOrNull { (it.unwatchedLeafCount ?: 0) > 0 } ?: regular.firstOrNull() ?: seasons.firstOrNull()
}

/** The trailer Play Trailer and the hero use: the one the server names first, else the first trailer listed. */
internal fun trailerOf(extras: List<PlexItem>, primaryKey: String?): PlexItem? {
	if (primaryKey != null) {
		extras.firstOrNull { it.key == primaryKey || primaryKey.endsWith("/${it.ratingKey}") }?.let { return it }
	}
	return extras.firstOrNull { it.subtype == "trailer" || it.extraType == 1 }
}

class DetailModel(private val container: AppContainer, private val route: Detail) : ViewModel() {
	private val session = container.session
	private val core = container.core
	private val changes = container.changes
	private val toasts = container.toasts
	private val mutable = MutableStateFlow(DetailState())
	val state: StateFlow<DetailState> = mutable.asStateFlow()

	init {
		viewModelScope.launch { loadAll(quiet = false) }
		viewModelScope.launch {
			var seen = changes.state.value.revision
			changes.state.collect { now ->
				if (now.revision == seen) return@collect
				val touched = now.keysAfter(seen)
				seen = now.revision
				val shown = mutable.value
				val mine = buildSet {
					shown.item?.let { addAll(listOfNotNull(it.ratingKey, it.parentRatingKey, it.grandparentRatingKey)) }
					shown.seasons.forEach { add(it.ratingKey) }
					shown.episodes?.forEach { add(it.ratingKey) }
				}
				if (touched.any { it in mine }) loadAll(quiet = true)
			}
		}
	}

	fun retry() {
		mutable.update { it.copy(loading = true, failure = null) }
		viewModelScope.launch { loadAll(quiet = false) }
	}

	fun chooseSeason(ratingKey: String) {
		if (mutable.value.seasonKey == ratingKey) return
		mutable.update { it.copy(seasonKey = ratingKey, episodes = null, episodesFailed = false) }
		viewModelScope.launch { loadEpisodes(ratingKey) }
	}

	/** Asks again for the listed season's episodes after they failed to load. */
	fun reloadEpisodes() {
		val key = mutable.value.seasonKey ?: return
		mutable.update { it.copy(episodes = null, episodesFailed = false) }
		viewModelScope.launch { loadEpisodes(key) }
	}

	private suspend fun loadAll(quiet: Boolean) {
		val catalogue = route.catalogue
		if (catalogue != null) {
			mutable.update { it.copy(item = catalogue, loading = false, onServer = false) }
			loadWatchlist(catalogue)
			return
		}
		val started = System.nanoTime()
		val full = try {
			session.onServer { server, remembered -> core.item(server, remembered, route.ratingKey) }
		} catch (e: CoreException) {
			Diag.warning(DiagCategory.Ui, "detail.failed", "kind" to e.kind.name, "status" to e.status)
			if (!quiet || mutable.value.item == null) mutable.update { it.copy(loading = false, failure = e.kind) }
			return
		}
		mutable.update { it.copy(item = full, loading = false, failure = null, onServer = true) }
		Diag.info(DiagCategory.Ui, "detail.loaded", "type" to full.type.name, "ms" to (System.nanoTime() - started) / 1_000_000)
		coroutineScope {
			launch { loadWatchlist(full) }
			launch { loadSeasons(full) }
			if (!quiet) {
				launch { loadExtras(full) }
				launch { loadRelated(full) }
				launch { loadInfo() }
			}
		}
	}

	private suspend fun loadSeasons(full: PlexItem) {
		when (full.type) {
			MediaType.Show -> {
				val seasons = quietly("seasons") { session.onServer { s, r -> core.children(s, r, full.ratingKey) } } ?: return
				val shown = changes.state.value.shown(seasons)
				val keep = mutable.value.seasonKey?.takeIf { key -> shown.any { it.ratingKey == key } }
				val chosen = keep ?: firstSeason(shown, full.onDeck)?.ratingKey
				mutable.update { it.copy(seasons = shown, seasonKey = chosen) }
				if (chosen != null) loadEpisodes(chosen)
			}
			MediaType.Season -> {
				mutable.update { it.copy(seasonKey = full.ratingKey) }
				loadEpisodes(full.ratingKey)
			}
			else -> Unit
		}
	}

	private suspend fun loadEpisodes(seasonKey: String) {
		val listed = quietly("episodes") { session.onServer { s, r -> core.children(s, r, seasonKey) } }
		mutable.update { now ->
			if (now.seasonKey != seasonKey) now else now.copy(episodes = listed?.let(changes.state.value::shown) ?: now.episodes, episodesFailed = listed == null && now.episodes == null)
		}
	}

	private suspend fun loadExtras(full: PlexItem) {
		if (full.type != MediaType.Movie && full.type != MediaType.Show && full.type != MediaType.Episode) return
		val extras = quietly("extras") { session.onServer { s, r -> core.extras(s, r, full.ratingKey) } } ?: return
		mutable.update { it.copy(extras = extras) }
	}

	private suspend fun loadRelated(full: PlexItem) {
		val related = quietly("related") { session.onServer { s, r -> core.related(s, r, full.ratingKey) } } ?: return
		mutable.update { it.copy(related = related.filter { hub -> hub.items.isNotEmpty() }) }
	}

	private suspend fun loadInfo() {
		val info = quietly("serverInfo") { session.onServer { s, r -> core.serverInfo(s, r) } } ?: return
		mutable.update { it.copy(info = info) }
	}

	private suspend fun loadWatchlist(full: PlexItem) {
		val token = session.accountToken
		val guid = full.guid
		if (token == null || guid == null || Watchlist.catalogueKey(guid) == null) return
		val listed = quietly("watchlist") { core.onWatchlist(token, guid) } ?: return
		mutable.update { it.copy(watchlisted = listed) }
	}

	/** A secondary piece of the page: when it cannot load the page goes without it, and the log says why. */
	private suspend fun <T> quietly(what: String, block: suspend () -> T): T? = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: CoreException) {
		Diag.warning(DiagCategory.Ui, "detail.$what.failed", "kind" to e.kind.name, "status" to e.status)
		null
	}

	/**
	 * Marks [ratingKey] watched or unwatched: the page shows it at once, the server is asked, and a message offers
	 * Undo once the server has agreed. A refusal puts the page back as it was.
	 */
	fun setWatched(ratingKey: String, watched: Boolean) {
		val before = mutable.value
		val target = before.find(ratingKey) ?: return
		val after = before.withWatched(ratingKey, watched)
		mutable.value = after
		container.scope.launch {
			if (send(target, watched)) {
				toasts.show(watchedMessage(target, before.item, watched), undo = { undoWatched(target, before, after, watched) })
			} else {
				mutable.update { restore(it, before) }
				toasts.show("Couldn't mark ${wording(target, before.item)} ${if (watched) "watched" else "unwatched"}.")
			}
		}
	}

	private fun undoWatched(target: PlexItem, before: DetailState, after: DetailState, watched: Boolean) {
		mutable.update { restore(it, before) }
		container.scope.launch {
			if (!send(target, !watched)) {
				mutable.update { restore(it, after) }
				toasts.show("Couldn't undo.")
			}
		}
	}

	/** Puts back the parts of the page a watched change touches, leaving what loaded since. */
	private fun restore(now: DetailState, saved: DetailState) =
		now.copy(item = saved.item, seasons = saved.seasons, episodes = if (now.seasonKey == saved.seasonKey) saved.episodes else now.episodes)

	private suspend fun send(target: PlexItem, watched: Boolean): Boolean = try {
		session.onServer { server, remembered -> core.setWatched(server, remembered, target.ratingKey, watched) }
		changes.record(setOfNotNull(target.ratingKey, target.parentRatingKey, target.grandparentRatingKey))
		true
	} catch (e: CoreException) {
		Diag.warning(DiagCategory.Ui, "detail.watched.failed", "kind" to e.kind.name, "status" to e.status)
		false
	}

	private fun watchedMessage(target: PlexItem, page: PlexItem?, watched: Boolean): String {
		val word = if (watched) "watched" else "unwatched"
		return when {
			target.type == MediaType.Show -> "Show marked $word"
			target.type == MediaType.Season -> "Season marked $word"
			page != null && target.ratingKey != page.ratingKey -> "Episode marked $word"
			else -> "Marked $word"
		}
	}

	private fun wording(target: PlexItem, page: PlexItem?): String = when {
		target.type == MediaType.Show -> "the show"
		target.type == MediaType.Season -> "the season"
		page != null && target.ratingKey != page.ratingKey -> "the episode"
		else -> "it"
	}

	/** Puts the title on the Watchlist or takes it off, at once, with a message that offers Undo. */
	fun toggleWatchlist() {
		val guid = mutable.value.item?.guid ?: return
		val token = session.accountToken ?: return
		val listed = mutable.value.watchlisted ?: return
		setWatchlisted(guid, token, !listed, undoable = true)
	}

	private fun setWatchlisted(guid: String, token: String, listed: Boolean, undoable: Boolean) {
		mutable.update { it.copy(watchlisted = listed) }
		container.scope.launch {
			try {
				if (listed) core.addToWatchlist(token, guid) else core.removeFromWatchlist(token, guid)
				if (listed) {
					changes.showInWatchlist(guid)
					changes.recordWatchlist()
				} else {
					changes.hideFromWatchlist(guid)
				}
				toasts.show(
					if (listed) "Added to Watchlist" else "Removed from Watchlist",
					undo = if (undoable) ({ setWatchlisted(guid, token, !listed, undoable = false) }) else null,
				)
			} catch (e: CoreException) {
				Diag.warning(DiagCategory.Ui, "detail.watchlist.failed", "kind" to e.kind.name, "status" to e.status)
				mutable.update { it.copy(watchlisted = !listed) }
				toasts.show("Couldn't update the Watchlist.")
			}
		}
	}

	/**
	 * Deletes [item] from the server's disk and takes it out of every list. It runs on the app's scope so a page
	 * closed meanwhile does not cancel it; [onResult] hears how the server's delete ended, on the main thread. The
	 * steps after it (the Watchlist, Radarr or Sonarr, and Seerr) are [MediaDeletions]'s.
	 */
	fun delete(item: PlexItem, onResult: (DeleteOutcome) -> Unit) {
		container.scope.launch { onResult(container.deletions.delete(item)) }
	}

	/** Deletes one version of [item] and its file; the other versions stay. */
	fun deleteVersion(item: PlexItem, mediaId: Int, label: String, onResult: (DeleteOutcome) -> Unit) {
		container.scope.launch {
			val outcome = try {
				session.onServer { server, remembered -> core.deleteVersion(server, remembered, item.ratingKey, mediaId) }.outcome
			} catch (e: CoreException) {
				Diag.warning(DiagCategory.Ui, "detail.deleteVersion.failed", "kind" to e.kind.name, "status" to e.status)
				DeleteOutcome.Failed
			}
			if (outcome == DeleteOutcome.Deleted) {
				changes.record(setOfNotNull(item.ratingKey, item.parentRatingKey, item.grandparentRatingKey))
				toasts.show("Deleted the $label version")
			}
			onResult(outcome)
		}
	}
}

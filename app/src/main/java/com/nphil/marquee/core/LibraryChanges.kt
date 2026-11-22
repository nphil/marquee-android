package com.nphil.marquee.core

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The catalogue half of a `plex://movie/5d776b59ad5437001f79c6f8` guid, which the Watchlist knows a title by. */
object Watchlist {
	/**
	 * The catalogue key of [guid]. Titles matched by an older agent (`com.plexapp.agents.imdb://…`) have none and
	 * cannot be watchlisted.
	 */
	fun catalogueKey(guid: String?): String? {
		if (guid == null || !guid.startsWith("plex://")) return null
		return guid.split('/').lastOrNull { it.isNotEmpty() }
	}

	/** The catalogue page on plex.tv, which anyone can open. */
	fun shareUrl(guid: String?): String? = catalogueKey(guid)?.let {
		"https://app.plex.tv/desktop/#!/provider/tv.plex.provider.discover/details?key=%2Flibrary%2Fmetadata%2F$it"
	}
}

/** Titles deleted during this session: Plex lists a deleted title until its trash is emptied. */
@Immutable
class DeletedTitles(val keys: Set<String> = emptySet()) {
	/** Whether [item] was deleted, or belongs to a deleted season or show. */
	fun contains(item: PlexItem): Boolean =
		item.ratingKey in keys || item.parentRatingKey in keys || item.grandparentRatingKey in keys

	/** [items] in their order, without the deleted ones. */
	fun removing(items: List<PlexItem>): List<PlexItem> = if (keys.isEmpty()) items else items.filter { !contains(it) }
}

/** What [LibraryChanges] has recorded so far. */
@Immutable
class Changes(
	/** Bumped after each confirmed watched or Continue Watching change. */
	val revision: Int = 0,
	/** Bumped after each confirmed Watchlist change. */
	val watchlistRevision: Int = 0,
	/** The revision that last touched each title, its season and its show. */
	private val touched: Map<String, Int> = emptyMap(),
	val deleted: DeletedTitles = DeletedTitles(),
	/** Catalogue keys of the titles taken off the Watchlist this session. */
	val unlisted: Set<String> = emptySet(),
	/** Cards taken off Continue Watching whose removal the server has not answered yet. */
	val removing: Set<String> = emptySet(),
) {
	/** The titles touched after [revision], which a screen that saw an older one refreshes. */
	fun keysAfter(revision: Int): Set<String> = touched.filterValues { it > revision }.keys

	/** [items] without the titles deleted this session. */
	fun shown(items: List<PlexItem>): List<PlexItem> = deleted.removing(items)

	/** [items] of Continue Watching without the cards being removed. */
	fun shownInContinueWatching(items: List<PlexItem>): List<PlexItem> =
		if (removing.isEmpty()) items else items.filter { it.ratingKey !in removing }

	/** [items] of the Watchlist without the titles taken off it this session. */
	fun shownInWatchlist(items: List<PlexItem>): List<PlexItem> {
		if (unlisted.isEmpty()) return items
		return items.filter { item -> Watchlist.catalogueKey(item.guid)?.let { it !in unlisted } ?: true }
	}

	internal fun recording(keys: Set<String>): Changes {
		val next = revision + 1
		return Changes(next, watchlistRevision, touched + keys.associateWith { next }, deleted, unlisted, removing)
	}

	internal fun copy(
		watchlistRevision: Int = this.watchlistRevision,
		deleted: DeletedTitles = this.deleted,
		unlisted: Set<String> = this.unlisted,
		removing: Set<String> = this.removing,
	) = Changes(revision, watchlistRevision, touched, deleted, unlisted, removing)
}

/**
 * Changes this app made on the server, so every screen that shows a changed title refreshes it, as the iPhone app's
 * LibraryChanges does. A screen remembers the [Changes.revision] it loaded at and loads again when it moves.
 */
class LibraryChanges {
	private val mutable = MutableStateFlow(Changes())
	val state: StateFlow<Changes> = mutable.asStateFlow()

	/** Notes that these titles changed on the server. */
	fun record(keys: Set<String>) = mutable.update { it.recording(keys) }

	/** Takes a deleted title out of every list; the screens showing its season or show load their counts again. */
	fun recordDeletion(item: PlexItem) = mutable.update { changes ->
		changes.copy(deleted = DeletedTitles(changes.deleted.keys + item.ratingKey))
			.recording(setOfNotNull(item.ratingKey, item.parentRatingKey, item.grandparentRatingKey))
	}

	/** Rating keys name titles on one server only, so choosing another server starts afresh. */
	fun forgetDeletions() = mutable.update { it.copy(deleted = DeletedTitles()) }

	fun recordWatchlist() = mutable.update { it.copy(watchlistRevision = it.watchlistRevision + 1) }

	/** The Watchlist screen leaves the title out at once, without waiting for plex.tv to stop listing it. */
	fun hideFromWatchlist(guid: String) = mutable.update { changes ->
		val key = Watchlist.catalogueKey(guid)
		val next = changes.copy(unlisted = if (key == null) changes.unlisted else changes.unlisted + key)
		next.copy(watchlistRevision = next.watchlistRevision + 1)
	}

	/** Undoes [hideFromWatchlist] for a title saved again. */
	fun showInWatchlist(guid: String) = mutable.update { changes ->
		val key = Watchlist.catalogueKey(guid) ?: return@update changes
		changes.copy(unlisted = changes.unlisted - key)
	}

	/**
	 * Marks a card as being taken off Continue Watching. A refetch in that time still lists it, so Home leaves it
	 * out until the server answers.
	 */
	fun startRemovingFromContinueWatching(key: String) = mutable.update { it.copy(removing = it.removing + key) }

	fun endRemovingFromContinueWatching(key: String) = mutable.update { it.copy(removing = it.removing - key) }
}

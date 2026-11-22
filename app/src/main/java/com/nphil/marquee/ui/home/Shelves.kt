package com.nphil.marquee.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nphil.marquee.R
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.core.minutesLeft
import com.nphil.marquee.core.posterTitle
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.spokenSummary
import com.nphil.marquee.ui.browse.ActionMenu
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.MenuAction
import com.nphil.marquee.ui.components.EpisodeCard
import com.nphil.marquee.ui.components.SectionHeader
import com.nphil.marquee.ui.components.ShelfRow
import com.nphil.marquee.ui.posterSharedElement
import com.nphil.marquee.ui.theme.Space

/** What Home's shelves do. The screen builds it from its model and the navigator; a test builds one that records. */
@Stable
class HomeActions(
	val open: (PlexItem) -> Unit,
	val openWatchlistTitle: (PlexItem) -> Unit,
	val seeAll: (HomeRow) -> Unit,
	val seeAllWatchlist: () -> Unit,
	val hideRow: (HomeRow) -> Unit,
	val editHome: () -> Unit,
	val markWatched: (item: PlexItem, index: Int) -> Unit,
	val markShowWatched: (show: String, cards: List<IndexedValue<PlexItem>>) -> Unit,
	val removeFromContinueWatching: (item: PlexItem, index: Int) -> Unit,
	val dismissRewatch: (item: PlexItem, index: Int) -> Unit,
	val setWatched: (watched: Boolean, item: PlexItem) -> Unit,
	val removeFromWatchlist: (PlexItem) -> Unit,
)

/** A shelf's title; a long press offers to hide the row or to edit Home. */
@Composable
private fun ShelfTitle(title: String, onSeeAll: (() -> Unit)?, actions: HomeActions, row: HomeRow?) {
	var menu by remember { mutableStateOf(false) }
	Box {
		SectionHeader(
			title = title,
			onSeeAll = onSeeAll,
			onLongClick = if (row == null) null else ({ menu = true }),
			onLongClickLabel = "Row options",
		)
		if (menu && row != null) {
			ActionMenu(
				expanded = true,
				onDismiss = { menu = false },
				actions = listOf(
					MenuAction(R.drawable.ic_visibility_off, "Hide This Row") { actions.hideRow(row) },
					MenuAction(R.drawable.ic_tune, "Edit Home") { actions.editHome() },
				),
			)
		}
	}
}

/**
 * The Continue Watching row: snapping 16:9 cards. A tap opens the title's page; a long press offers Details, Mark Watched,
 * Mark Show Watched (on an episode) and Remove from Continue Watching. Removing a card made for a show watched again hides
 * it until the show is viewed again.
 */
@Composable
internal fun ContinueShelf(row: HomeRow, cardWidth: Dp, actions: HomeActions, modifier: Modifier = Modifier) {
	val cards = remember(row.hub.items) { row.hub.items.withIndex().toList() }
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		ShelfTitle(row.title, onSeeAll = null, actions = actions, row = row)
		ShelfRow(cards, key = { it.value.ratingKey }) { (index, item) ->
			ContinueCard(item, index, cards, cardWidth, actions)
		}
	}
}

@Composable
private fun ContinueCard(
	item: PlexItem,
	index: Int,
	cards: List<IndexedValue<PlexItem>>,
	width: Dp,
	actions: HomeActions,
) {
	var menu by remember { mutableStateOf(false) }
	Box {
		EpisodeCard(
			title = item.posterTitle,
			artPath = item.cardArtPath,
			width = width,
			line = item.continueLine,
			progress = item.progress,
			description = item.spokenSummary,
			onClick = { actions.open(item) },
			onLongClick = { menu = true },
			onLongClickLabel = "More options",
			artModifier = Modifier.posterSharedElement(item.ratingKey),
		)
		if (menu) {
			ActionMenu(
				expanded = true,
				onDismiss = { menu = false },
				actions = continueMenu(item, index, cards, actions),
			)
		}
	}
}

private fun continueMenu(item: PlexItem, index: Int, cards: List<IndexedValue<PlexItem>>, actions: HomeActions): List<MenuAction> {
	val show = item.grandparentRatingKey
	return buildList {
		add(MenuAction(R.drawable.ic_info, "Details") { actions.open(item) })
		add(MenuAction(R.drawable.ic_check_circle, "Mark Watched") { actions.markWatched(item, index) })
		if (item.type == MediaType.Episode && show != null) {
			add(MenuAction(R.drawable.ic_check_circle_filled, "Mark Show Watched") {
				// Every card of the show leaves at once, as the server drops its Continue Watching entries.
				actions.markShowWatched(show, cards.filter { it.value.grandparentRatingKey == show })
			})
		}
		add(MenuAction(R.drawable.ic_remove_circle, "Remove from Continue Watching") {
			if (item.rewatchedAt != null) actions.dismissRewatch(item, index) else actions.removeFromContinueWatching(item, index)
		})
	}
}

/** What a Continue Watching card draws: an episode its show's backdrop, a movie its own. */
internal val PlexItem.cardArtPath: String?
	get() = if (type == MediaType.Episode) grandparentArt ?: art ?: thumb else art ?: thumb

/** "S2 · E5 · 42 min left". */
private val PlexItem.continueLine: String?
	get() = listOfNotNull(episodeCode, minutesLeft?.let(ItemText::left)).joinToString(" · ").ifEmpty { null }

/**
 * A row of posters under its header; each opens its page. A show's long press offers to mark it watched or unwatched. A
 * hub the server holds more of has "See all".
 */
@Composable
internal fun PosterShelf(row: HomeRow, posterWidth: Dp, actions: HomeActions, modifier: Modifier = Modifier) {
	val seeAll = if (row.hub.more && row.hub.key != null) ({ actions.seeAll(row) }) else null
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		ShelfTitle(row.title, onSeeAll = seeAll, actions = actions, row = row)
		ShelfRow(row.hub.items, key = { it.ratingKey }) { item ->
			ShowMenuPoster(item, posterWidth, actions)
		}
	}
}

@Composable
private fun ShowMenuPoster(item: PlexItem, width: Dp, actions: HomeActions) {
	var menu by remember { mutableStateOf(false) }
	val isShow = item.type == MediaType.Show
	Box {
		ItemPoster(
			item = item,
			width = width,
			onClick = { actions.open(item) },
			onLongClick = if (isShow) ({ menu = true }) else null,
			onLongClickLabel = if (isShow) "More options" else null,
		)
		if (menu) {
			ActionMenu(
				expanded = true,
				onDismiss = { menu = false },
				actions = listOf(
					if (item.isWatched) {
						MenuAction(R.drawable.ic_radio_button_unchecked, "Mark Show Unwatched") { actions.setWatched(false, item) }
					} else {
						MenuAction(R.drawable.ic_check_circle, "Mark Show Watched") { actions.setWatched(true, item) }
					},
				),
			)
		}
	}
}

/**
 * The Watchlist's latest titles as a Home shelf, while Watchlist is not a tab (DESIGN §7, §8.1). Titles the server does
 * not hold open the catalogue page; a long press takes one off the watchlist.
 */
@Composable
internal fun WatchlistShelf(titles: List<PlexItem>, more: Boolean, posterWidth: Dp, actions: HomeActions, modifier: Modifier = Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		ShelfTitle("Watchlist", onSeeAll = if (more) actions.seeAllWatchlist else null, actions = actions, row = null)
		ShelfRow(titles, key = { it.ratingKey }) { item ->
			var menu by remember { mutableStateOf(false) }
			val removable = item.guid != null
			Box {
				ItemPoster(
					item = item,
					width = posterWidth,
					onClick = { actions.openWatchlistTitle(item) },
					onLongClick = if (removable) ({ menu = true }) else null,
					onLongClickLabel = if (removable) "More options" else null,
				)
				if (menu) {
					ActionMenu(
						expanded = true,
						onDismiss = { menu = false },
						actions = listOf(
							MenuAction(R.drawable.ic_bookmark_remove, "Remove from Watchlist", destructive = true) { actions.removeFromWatchlist(item) },
						),
					)
				}
			}
		}
	}
}

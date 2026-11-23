package com.nphil.marquee.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.children
import com.nphil.marquee.core.minutesLeft
import com.nphil.marquee.core.progress
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.ProgressBar
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** How the episodes are laid out: the side panel of a wide window, or the bottom sheet of a narrow one. */
internal enum class EpisodeLayout(val stillWidth: Dp, val stillHeight: Dp) {
	Side(104.dp, 58.dp),
	Sheet(128.dp, 72.dp),
}

/** The fraction of a narrow window's height the bottom sheet takes. */
private const val SHEET_HEIGHT = 0.62f

private val CURRENT_EDGE = 2.dp

/** "3 · The Name": the episode's number and title. */
internal fun episodeTitle(episode: PlexItem): String = listOfNotNull(episode.index?.toString(), episode.title).joinToString(" · ")

/**
 * What the row says under the title: that it is playing, that it is watched, the time left of one that is started, else
 * its length; null when nothing is known. The playing episode's own progress is the player's, not the list's.
 */
internal fun episodeStatus(episode: PlexItem, playing: Boolean): String? = when {
	playing -> "Now playing"
	episode.isWatched -> "Watched"
	else -> episode.minutesLeft?.let(ItemText::left) ?: ItemText.runtime(episode.duration)
}

/** What TalkBack reads for a row: "Episode 3, The Name, Watched". */
internal fun episodeDescription(episode: PlexItem, playing: Boolean): String =
	listOfNotNull(episode.index?.let { "Episode $it" }, episode.title, episodeStatus(episode, playing)).joinToString(", ")

/** Where the list starts: a row above the playing one when it is in the season, else the top. */
internal fun firstVisibleEpisode(episodes: List<PlexItem>, playingKey: String?): Int {
	val at = episodes.indexOfFirst { it.ratingKey == playingKey }
	return if (at < 0) 0 else (at - 1).coerceAtLeast(0)
}

/**
 * The show's seasons as tabs and the open season's episodes below, each with its still, how far in, whether it is
 * watched and what it is called; the playing one is marked. A tap plays an episode from where it was left, a long press
 * from the beginning. A show of one season has no tabs. The player's own track and route rules apply to the episode, as
 * they do to the next one.
 */
@Composable
internal fun EpisodesPage(model: PlayerModel, layout: EpisodeLayout, onClose: () -> Unit, modifier: Modifier = Modifier) {
	val container = LocalContext.current.container
	val playing = model.plan?.item
	val showKey = playing?.grandparentRatingKey
	val playingKey = playing?.ratingKey
	var seasons by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
	// The season the viewer opened; until one is, the playing episode's own.
	var opened by rememberSaveable { mutableStateOf<String?>(null) }
	val seasonKey = opened ?: playing?.parentRatingKey
	var episodes by remember { mutableStateOf<List<PlexItem>?>(null) }
	var failed by remember { mutableStateOf(false) }
	var attempt by remember { mutableIntStateOf(0) }

	LaunchedEffect(showKey, attempt) {
		val show = showKey ?: return@LaunchedEffect
		// Without the list of seasons the open season's episodes still show, with its name in place of the tabs.
		seasons = try {
			container.session.onServer { server, remembered -> container.core.children(server, remembered, show) }.filter { it.type == MediaType.Season }
		} catch (_: CoreException) {
			emptyList()
		}
	}
	LaunchedEffect(seasonKey, attempt) {
		val season = seasonKey ?: return@LaunchedEffect
		episodes = null
		failed = false
		try {
			episodes = container.session.onServer { server, remembered -> container.core.children(server, remembered, season) }
		} catch (_: CoreException) {
			failed = true
		}
	}

	val listState = rememberLazyListState()
	val shown = episodes
	LaunchedEffect(shown?.map { it.ratingKey }) {
		if (shown != null) listState.scrollToItem(firstVisibleEpisode(shown, playingKey))
	}

	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		SeasonTabs(seasons, seasonKey, playing?.parentTitle) { key ->
			if (key != seasonKey) {
				episodes = null
				failed = false
				opened = key
			}
		}
		when {
			failed -> Box(Modifier.fillMaxWidth().padding(vertical = Space.s5), contentAlignment = Alignment.Center) {
				RetryButton("Couldn't load the episodes. Retry") { attempt++ }
			}
			shown == null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				repeat(3) {
					Box(Modifier.fillMaxWidth().height(layout.stillHeight).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
				}
			}
			shown.isEmpty() -> Text("No episodes", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2, modifier = Modifier.padding(Space.s3))
			else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
				items(shown, key = { it.ratingKey }, contentType = { "episode" }) { episode ->
					EpisodeRow(
						episode, episode.ratingKey == playingKey, layout,
						onPlay = {
							model.playEpisode(episode, restart = false)
							onClose()
						},
						onRestart = {
							model.playEpisode(episode, restart = true)
							onClose()
						},
					)
				}
			}
		}
	}
}

/** The seasons as pills in a row that scrolls, the open one in the accent; one season, or none known, is only its name. */
@Composable
private fun SeasonTabs(seasons: List<PlexItem>, open: String?, fallbackName: String?, onOpen: (String) -> Unit) {
	if (seasons.size < 2) {
		val name = seasons.firstOrNull()?.title ?: fallbackName
		if (name != null) Text(name, style = MaterialTheme.typography.titleMedium, color = lucent.ink2, modifier = Modifier.padding(horizontal = Space.s3))
		return
	}
	val state = rememberLazyListState()
	LaunchedEffect(seasons.size) {
		val at = seasons.indexOfFirst { it.ratingKey == open }
		if (at > 0) state.scrollToItem(at - 1)
	}
	LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(Space.s2), contentPadding = PaddingValues(horizontal = Space.s1)) {
		itemsIndexed(seasons, key = { _, season -> season.ratingKey }) { _, season ->
			val selected = season.ratingKey == open
			Box(
				Modifier
					.heightIn(min = Size.target)
					.clip(CircleShape)
					.background(if (selected) lucent.accent else lucent.glass)
					.combinedClickable(role = Role.Tab, onClickLabel = "Show ${season.title}", onClick = { onOpen(season.ratingKey) })
					.semantics { this.selected = selected }
					.padding(horizontal = Space.s4),
				contentAlignment = Alignment.Center,
			) {
				Text(
					season.title,
					style = MaterialTheme.typography.titleSmall,
					color = if (selected) lucent.accentInk else lucent.ink,
					maxLines = 1,
				)
			}
		}
	}
}

@Composable
private fun EpisodeRow(episode: PlexItem, playing: Boolean, layout: EpisodeLayout, onPlay: () -> Unit, onRestart: () -> Unit) {
	val shape = RoundedCornerShape(Radius.control)
	val progress = episode.progress.takeUnless { playing }
	val status = episodeStatus(episode, playing)
	Row(
		Modifier
			.fillMaxWidth()
			.clip(shape)
			.then(if (playing) Modifier.background(lucent.glassRaised).border(CURRENT_EDGE, lucent.accent, shape) else Modifier)
			.combinedClickable(
				role = Role.Button,
				onClickLabel = "Play ${episode.title}",
				onLongClickLabel = "Play from the beginning",
				onLongClick = onRestart,
				onClick = onPlay,
			)
			.semantics(mergeDescendants = true) {
				contentDescription = episodeDescription(episode, playing)
				selected = playing
				customActions = listOf(CustomAccessibilityAction("Play from the beginning") { onRestart(); true })
			}
			.padding(Space.s2),
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Column(Modifier.width(layout.stillWidth), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			PlexArt(episode.thumb, layout.stillWidth, layout.stillHeight, Modifier.clip(RoundedCornerShape(Radius.control)))
			if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth())
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(episodeTitle(episode), style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
			if (status != null) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
					val icon = if (playing) R.drawable.ic_volume_up else if (episode.isWatched) R.drawable.ic_check_circle_filled else null
					if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = if (playing) lucent.accentText else lucent.ink2, modifier = Modifier.size(16.dp))
					Text(status, style = MaterialTheme.typography.bodySmall, color = if (playing) lucent.accentText else lucent.ink2, maxLines = 1)
				}
			}
			if (episode.summary.isNotBlank()) {
				Text(episode.summary, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

/**
 * The episodes over the bottom of a narrow window: a sheet that takes [SHEET_HEIGHT] of the height, under the controls'
 * own bars, with its name and a way to close it. [onTouch] hears every touch on it, for the panel's dwell.
 */
@Composable
internal fun EpisodeSheet(model: PlayerModel, onClose: () -> Unit, onTouch: () -> Unit, modifier: Modifier = Modifier) {
	Column(
		modifier
			.fillMaxWidth()
			.fillMaxHeight(SHEET_HEIGHT)
			.background(lucent.canvas, RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
			.safeDrawingPadding()
			.padding(horizontal = Space.s3)
			.watchesTouch(onTouch),
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Row(Modifier.fillMaxWidth().padding(top = Space.s2), verticalAlignment = Alignment.CenterVertically) {
			Text("Episodes", style = MaterialTheme.typography.titleLarge, color = lucent.ink, modifier = Modifier.weight(1f).padding(start = Space.s3))
			IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = lucent.ink) }
		}
		EpisodesPage(model, EpisodeLayout.Sheet, onClose, Modifier.weight(1f))
	}
}

package com.nphil.marquee.ui.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.progress
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.ProgressBar
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

private val StillWidth = 128.dp
private val StillHeight = 72.dp

/**
 * A show's season picker and episodes, or a season's episodes (DESIGN §8.4). The episodes run in one column, two
 * where the page is wide, each its own lazy row so a long season scrolls without composing it all.
 */
fun LazyListScope.seasonsSection(state: DetailState, model: DetailModel, navigator: Navigator, wide: Boolean) {
	val page = state.item ?: return
	val isShow = page.type == MediaType.Show
	if (!isShow && page.type != MediaType.Season) return
	item(key = "seasons", contentType = "seasons") {
		Row(
			Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.s1, top = Space.s6),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (isShow) {
				SeasonPicker(state, model)
				Spacer(Modifier.weight(1f))
				SeasonMenu(state, model)
			} else {
				Text("Episodes", style = MaterialTheme.typography.headlineSmall, color = lucent.ink, modifier = Modifier.weight(1f).semantics { heading() })
				page.unwatchedLeafCount?.takeIf { it > 0 }?.let {
					Text("$it unwatched", style = MaterialTheme.typography.labelLarge, color = lucent.ink2, modifier = Modifier.padding(end = Space.s3))
				}
			}
		}
	}
	val episodes = state.episodes
	when {
		episodes == null -> item(key = "episodes-wait", contentType = "wait") {
			Box(Modifier.fillMaxWidth().padding(vertical = Space.s5), contentAlignment = Alignment.Center) {
				if (state.episodesFailed) {
					RetryButton("Couldn't load the episodes. Retry", onClick = model::reloadEpisodes)
				} else {
					Column(Modifier.fillMaxWidth().padding(horizontal = Space.inset), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
						repeat(3) {
							Box(Modifier.fillMaxWidth().height(StillHeight).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
						}
					}
				}
			}
		}
		episodes.isEmpty() -> item(key = "episodes-none", contentType = "wait") {
			Text("No episodes", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2, modifier = Modifier.padding(horizontal = Space.inset, vertical = Space.s4))
		}
		wide -> items(episodes.chunked(2), key = { it.first().ratingKey }, contentType = { "episodes" }) { pair ->
			Row(Modifier.fillMaxWidth().padding(horizontal = Space.inset), horizontalArrangement = Arrangement.spacedBy(Space.s5)) {
				pair.forEach { EpisodeRow(it, model, navigator, Modifier.weight(1f)) }
				if (pair.size == 1) Spacer(Modifier.weight(1f))
			}
		}
		else -> items(episodes, key = { it.ratingKey }, contentType = { "episode" }) {
			EpisodeRow(it, model, navigator, Modifier.padding(horizontal = Space.inset))
		}
	}
}

/** "Season 2 · 3 unwatched". */
internal fun seasonLabel(season: PlexItem): String {
	val unwatched = season.unwatchedLeafCount ?: 0
	return if (unwatched > 0) "${season.title} · $unwatched unwatched" else season.title
}

@Composable
private fun SeasonPicker(state: DetailState, model: DetailModel) {
	var open by remember { mutableStateOf(false) }
	val current = state.season ?: return
	Box {
		Row(
			modifier = Modifier
				.heightIn(min = Size.target)
				.clip(CircleShape)
				.background(lucent.glassRaised)
				.border(1.dp, lucent.edgeRaised, CircleShape)
				.clickable(role = Role.Button, onClickLabel = "Choose a season") { open = true }
				.padding(horizontal = Space.s4),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s2),
		) {
			Text(seasonLabel(current), style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(20.dp))
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			state.seasons.forEach { season ->
				DropdownMenuItem(
					text = { Text(seasonLabel(season)) },
					leadingIcon = if (season.ratingKey == current.ratingKey) ({ Icon(painterResource(R.drawable.ic_check), contentDescription = null) }) else null,
					onClick = {
						open = false
						model.chooseSeason(season.ratingKey)
					},
				)
			}
		}
	}
}

/** Marks the whole season watched or unwatched. */
@Composable
private fun SeasonMenu(state: DetailState, model: DetailModel) {
	var open by remember { mutableStateOf(false) }
	val season = state.season ?: return
	Box {
		IconTarget(R.drawable.ic_more_horiz, "Season options", lucent.ink2) { open = true }
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(
				text = { Text(if (season.isWatched) "Mark Season Unwatched" else "Mark Season Watched") },
				leadingIcon = { Icon(painterResource(if (season.isWatched) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle), contentDescription = null) },
				onClick = {
					open = false
					model.setWatched(season.ratingKey, !season.isWatched)
				},
			)
		}
	}
}

/** A 128 by 72 still with a bar for how far in, the number and name, the runtime and air date, two lines of the summary. */
@Composable
private fun EpisodeRow(episode: PlexItem, model: DetailModel, navigator: Navigator, modifier: Modifier) {
	var menu by remember { mutableStateOf(false) }
	val progress = episode.progress
	Row(
		modifier = modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(Radius.tile))
			.clickable(role = Role.Button, onClickLabel = "Open ${episode.title}") { navigator.openItem(episode.ratingKey) }
			.padding(vertical = Space.s2),
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Column(Modifier.width(StillWidth), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			PlexArt(episode.thumb, StillWidth, StillHeight, Modifier.clip(RoundedCornerShape(Radius.control)))
			if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth())
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				listOfNotNull(episode.index?.toString(), episode.title).joinToString(" · "),
				style = MaterialTheme.typography.titleMedium,
				color = lucent.ink,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			val meta = listOfNotNull(ItemText.runtime(episode.duration), ItemText.airDate(episode.originallyAvailableAt)).joinToString(" · ")
			if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (episode.summary.isNotBlank()) {
				Text(episode.summary, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
		}
		Column {
			IconTarget(
				icon = if (episode.isWatched) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle,
				label = if (episode.isWatched) "Mark ${episode.title} unwatched" else "Mark ${episode.title} watched",
				tint = if (episode.isWatched) lucent.accentText else lucent.ink2,
			) { model.setWatched(episode.ratingKey, !episode.isWatched) }
			Box {
				IconTarget(R.drawable.ic_more_horiz, "More for ${episode.title}", lucent.ink2) { menu = true }
				DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
					DropdownMenuItem(
						text = { Text(if (episode.isWatched) "Mark Unwatched" else "Mark Watched") },
						leadingIcon = { Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null) },
						onClick = {
							menu = false
							model.setWatched(episode.ratingKey, !episode.isWatched)
						},
					)
					DropdownMenuItem(
						text = { Text("Details") },
						leadingIcon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
						onClick = {
							menu = false
							navigator.openItem(episode.ratingKey)
						},
					)
				}
			}
		}
	}
}

/** A 48 dp glyph button with no fill, for the quiet actions in a list. */
@Composable
private fun IconTarget(@DrawableRes icon: Int, label: String, tint: Color, onClick: () -> Unit) {
	Box(
		modifier = Modifier
			.size(Size.target)
			.clip(CircleShape)
			.clickable(role = Role.Button, onClick = onClick)
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center,
	) {
		Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
	}
}

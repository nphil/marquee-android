package com.nphil.marquee.ui.detail

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
import kotlin.math.roundToInt

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

/**
 * A 128 by 72 still with a bar for how far in, the number and name, the runtime and air date, two lines of the
 * summary. A swipe from the start edge marks it watched or unwatched, as on the iPhone; the check and the More menu
 * do the same without one.
 */
@Composable
private fun EpisodeRow(episode: PlexItem, model: DetailModel, navigator: Navigator, modifier: Modifier) {
	SwipeToMarkWatched(episode.isWatched, modifier, onToggle = { model.setWatched(episode.ratingKey, !episode.isWatched) }) {
		EpisodeRowContent(episode, model, navigator)
	}
}

@Composable
private fun EpisodeRowContent(episode: PlexItem, model: DetailModel, navigator: Navigator) {
	var menu by remember { mutableStateOf(false) }
	val progress = episode.progress
	Row(
		modifier = Modifier
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

private val SwipeCommit = 96.dp
private val SwipeFling = 400.dp

/**
 * Shows an accent strip under the row as it is dragged from the start edge and springs back when let go. A drag past
 * [SwipeCommit], or a fling, takes effect, as the leading swipe action does in the iPhone's episode lists.
 */
@Composable
private fun SwipeToMarkWatched(watched: Boolean, modifier: Modifier, onToggle: () -> Unit, content: @Composable () -> Unit) {
	val haptics = LocalHapticFeedback.current
	val density = LocalDensity.current
	val reach = with(density) { SwipeCommit.toPx() }
	val fling = with(density) { SwipeFling.toPx() }
	val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
	var dragged by remember { mutableFloatStateOf(0f) }
	val swiping by remember { derivedStateOf { dragged > 0f } }
	Box(
		modifier
			.draggable(
				orientation = Orientation.Horizontal,
				state = rememberDraggableState { delta -> dragged = (dragged + delta * direction).coerceIn(0f, reach * 2) },
				onDragStopped = { velocity ->
					if (dragged >= reach || velocity * direction > fling) {
						haptics.performHapticFeedback(HapticFeedbackType.Confirm)
						onToggle()
					}
					animate(dragged, 0f, animationSpec = spring()) { value, _ -> dragged = value }
				},
			)
			.semantics {
				customActions = listOf(CustomAccessibilityAction(if (watched) "Mark unwatched" else "Mark watched") { onToggle(); true })
			},
	) {
		if (swiping) {
			Row(
				Modifier.matchParentSize().clip(RoundedCornerShape(Radius.tile)).background(lucent.accent).padding(horizontal = Space.s5),
				horizontalArrangement = Arrangement.spacedBy(Space.s2),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(painterResource(if (watched) R.drawable.ic_radio_button_unchecked else R.drawable.ic_check_circle), contentDescription = null, tint = lucent.accentInk)
				Text(if (watched) "Unwatched" else "Watched", style = MaterialTheme.typography.labelLarge, color = lucent.accentInk, maxLines = 1)
			}
		}
		// Opaque only while the row is away from its place, so the strip does not show through it.
		Box(Modifier.offset { IntOffset((dragged * direction).roundToInt(), 0) }.then(if (swiping) Modifier.background(lucent.canvas) else Modifier)) {
			content()
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

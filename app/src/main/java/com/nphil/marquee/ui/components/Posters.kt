package com.nphil.marquee.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.roundToInt

/** A poster's art is 2:3, in whole dp so the pixels asked of the server match on every screen. */
fun posterHeight(width: Dp): Dp = (width.value * 1.5f).roundToInt().dp

/** A card's 16:9 art, in whole dp. */
fun cardHeight(width: Dp): Dp = (width.value * 9f / 16f).roundToInt().dp

/** A 3 dp progress track: accent on the off track (DESIGN §8.1). Decoration; the label carries the time left. */
@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(lucent.trackOff)) {
		Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(lucent.accent))
	}
}

/**
 * The badge on a poster's top trailing corner: the unwatched-episode count, or a check once watched. A solid
 * canvas pill with ink text, never glass (DESIGN §8.1).
 */
@Composable
fun BoxScope.PosterBadge(unwatchedCount: Int?, watched: Boolean) {
	if (unwatchedCount == null && !watched) return
	Row(
		modifier = Modifier
			.align(Alignment.TopEnd)
			.padding(Space.s2)
			.widthIn(min = Space.s6)
			.clip(CircleShape)
			.background(lucent.canvas)
			.padding(horizontal = Space.s2, vertical = Space.s1 / 2),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (unwatchedCount != null) {
			Text(
				unwatchedCount.toString(),
				style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
				color = lucent.ink,
			)
		} else {
			Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.ink, modifier = Modifier.size(14.dp))
		}
	}
}

/**
 * Press feedback for posters and cards: the .97 scale (DESIGN §9), and a haptic tick on a long press. The
 * scale is read where the layer draws, so a press recomposes nothing. The system's animation scale at zero
 * skips it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
): Modifier {
	val source = remember { MutableInteractionSource() }
	val pressed by source.collectIsPressedAsState()
	val scale = animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
	val haptics = LocalHapticFeedback.current
	val longClick = onLongClick?.let { action ->
		{
			haptics.performHapticFeedback(HapticFeedbackType.LongPress)
			action()
		}
	}
	return this
		.graphicsLayer {
			scaleX = scale.value
			scaleY = scale.value
		}
		.combinedClickable(
			interactionSource = source,
			indication = null,
			onClick = onClick,
			onLongClick = longClick,
			onLongClickLabel = onLongClickLabel,
		)
}

/**
 * A poster in a shelf or grid: 2:3 art with its badge, a progress track when part-watched (kept as empty
 * space otherwise, so titles line up), then a one-line caption title and a details or sort-value [line]
 * (DESIGN §8.1). [reserveLine] keeps room for the line on a title that has none, so a grid row lines up.
 * [description] replaces what a screen reader says, which is the title and its watched state. [artModifier]
 * applies to the art alone, which is where a detail page's shared element starts; [onLongClickLabel] names
 * what a long press does for screen readers.
 */
@Composable
fun PosterCard(
	title: String,
	artPath: String?,
	width: Dp,
	modifier: Modifier = Modifier,
	line: String? = null,
	reserveLine: Boolean = false,
	quietLine: Boolean = false,
	progress: Float? = null,
	watched: Boolean = false,
	unwatchedCount: Int? = null,
	description: String? = null,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
	artModifier: Modifier = Modifier,
) {
	val shape = RoundedCornerShape(if (width >= 96.dp) Radius.tile else Radius.control)
	val spoken = description ?: spokenTitle(title, watched, unwatchedCount, progress)
	Column(
		modifier = modifier
			.width(width)
			.pressable(onClick, onLongClick, onLongClickLabel)
			.semantics(mergeDescendants = true) { contentDescription = spoken },
		verticalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		Box {
			PlexArt(artPath, width, posterHeight(width), artModifier.clip(shape))
			PosterBadge(unwatchedCount, watched)
		}
		if (progress != null) ProgressBar(progress) else Spacer(Modifier.height(3.dp))
		Column(Modifier.clearAndSetSemantics { }) {
			Text(
				title,
				style = MaterialTheme.typography.bodySmall,
				fontWeight = if (reserveLine || line != null) FontWeight.SemiBold else null,
				color = lucent.ink,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (reserveLine || line != null) {
				// A space keeps the room when this title has no value, so a grid row lines up.
				Text(
					line ?: " ",
					style = MaterialTheme.typography.bodySmall,
					color = if (quietLine) lucent.ink3 else lucent.ink2,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/**
 * A landscape card: 16:9 art, then under it, never over it, the progress, the [title] and a [line] such as
 * "S2 · E5 · 42 min left" (DESIGN §8.1).
 */
@Composable
fun EpisodeCard(
	title: String,
	artPath: String?,
	width: Dp,
	modifier: Modifier = Modifier,
	line: String? = null,
	progress: Float? = null,
	description: String? = null,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
	artModifier: Modifier = Modifier,
) {
	val spoken = description ?: listOfNotNull(title, line).joinToString(", ")
	Column(
		modifier = modifier
			.width(width)
			.pressable(onClick, onLongClick, onLongClickLabel)
			.semantics(mergeDescendants = true) { contentDescription = spoken },
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		PlexArt(artPath, width, cardHeight(width), artModifier.clip(RoundedCornerShape(Radius.card)))
		if (progress != null) ProgressBar(progress) else Spacer(Modifier.height(3.dp))
		Column(Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(Space.s1 / 2)) {
			Text(
				title,
				style = MaterialTheme.typography.titleMedium,
				color = lucent.ink,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			if (line != null) {
				Text(line, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

/**
 * A shelf's title, a heading for screen readers, with "See all" as a trailing chevron (DESIGN §8.1). A long
 * press on the title offers [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	onSeeAll: (() -> Unit)? = null,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
) {
	val haptics = LocalHapticFeedback.current
	Row(
		modifier = modifier.fillMaxWidth().padding(start = Space.inset, end = Space.s1),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val text = Modifier
			.weight(1f)
			.heightIn(min = Size.target)
			.semantics { heading() }
		Text(
			title,
			style = MaterialTheme.typography.headlineSmall,
			color = lucent.ink,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = if (onLongClick == null) {
				text.padding(vertical = Space.s2)
			} else {
				text.combinedClickable(
					onClick = {},
					onLongClick = {
						haptics.performHapticFeedback(HapticFeedbackType.LongPress)
						onLongClick()
					},
					onLongClickLabel = onLongClickLabel,
				).padding(vertical = Space.s2)
			},
		)
		if (onSeeAll != null) {
			Box(
				modifier = Modifier
					.size(Size.target)
					.clip(CircleShape)
					.clickable(role = Role.Button, onClickLabel = "See all $title", onClick = onSeeAll),
				contentAlignment = Alignment.Center,
			) {
				Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = lucent.ink2)
			}
		}
	}
}

/** One horizontal shelf: its items side by side, snapping to the leading edge as the iPhone's shelves do. */
@Composable
fun <T> ShelfRow(
	items: List<T>,
	key: (T) -> Any,
	modifier: Modifier = Modifier,
	state: LazyListState = rememberLazyListState(),
	contentPadding: PaddingValues = PaddingValues(horizontal = Space.inset),
	itemContent: @Composable (T) -> Unit,
) {
	LazyRow(
		modifier = modifier,
		state = state,
		contentPadding = contentPadding,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
		verticalAlignment = Alignment.Top,
		flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
	) {
		items(items, key = key) { item -> itemContent(item) }
	}
}

private fun spokenTitle(title: String, watched: Boolean, unwatchedCount: Int?, progress: Float?): String = buildString {
	append(title)
	when {
		unwatchedCount != null -> append(if (unwatchedCount == 1) ", 1 unwatched episode" else ", $unwatchedCount unwatched episodes")
		watched -> append(", watched")
		progress != null -> append(", in progress")
	}
}

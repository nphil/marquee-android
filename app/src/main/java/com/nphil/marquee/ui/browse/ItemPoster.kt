package com.nphil.marquee.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.core.posterTitle
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.spokenSummary
import com.nphil.marquee.core.unwatchedBadge
import com.nphil.marquee.ui.components.PosterCard
import com.nphil.marquee.ui.posterSharedElement

/**
 * A title's poster as a shelf or a grid draws it: its art, badge and progress, and the caption the screen picks. The art
 * is where the title's page grows from when the poster is tapped.
 */
@Composable
fun ItemPoster(
	item: PlexItem,
	width: Dp,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	line: String? = null,
	reserveLine: Boolean = false,
	quietLine: Boolean = false,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
) {
	PosterCard(
		title = item.posterTitle,
		artPath = item.posterPath,
		width = width,
		modifier = modifier,
		line = line,
		reserveLine = reserveLine,
		quietLine = quietLine,
		progress = item.progress,
		watched = item.showsWatchedCheck,
		unwatchedCount = item.unwatchedBadge,
		description = if (line == null) item.spokenSummary else "${item.spokenSummary}, $line",
		onClick = onClick,
		onLongClick = onLongClick,
		onLongClickLabel = onLongClickLabel,
		artModifier = Modifier.posterSharedElement(item.ratingKey),
	)
}

/** A watched title has a check on its poster; a collection or a clip has none. */
private val PlexItem.showsWatchedCheck: Boolean
	get() = isWatched && type != MediaType.Collection && type != MediaType.Clip

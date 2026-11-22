package com.nphil.marquee.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nphil.marquee.browse.DayClock
import com.nphil.marquee.browse.posterLine
import com.nphil.marquee.browse.reservesLine
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.core.posterTitle
import com.nphil.marquee.core.progress
import com.nphil.marquee.core.showsWatched
import com.nphil.marquee.core.spokenSummary
import com.nphil.marquee.core.unwatchedBadge
import com.nphil.marquee.data.PosterLabels
import com.nphil.marquee.ui.components.PosterCard

internal val LocalPosterLabels = compositionLocalOf { PosterLabels() }

/**
 * A title's poster in a shelf or grid: its art, badge, progress and the active profile's captions.
 */
@Composable
internal fun ItemPoster(
	item: PlexItem,
	width: Dp,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
	sort: Sort? = null,
	clock: DayClock? = null,
	onLongClick: (() -> Unit)? = null,
	onLongClickLabel: String? = null,
) {
	val labels = LocalPosterLabels.current
	val reserveLine = labels.reservesLine(sort)
	val line = if (reserveLine) remember(item, labels, sort, clock) {
		item.posterLine(labels, sort, clock)
	} else null
	PosterCard(
		title = item.posterTitle,
		artPath = item.posterPath,
		width = width,
		modifier = modifier,
		line = line?.text,
		reserveLine = reserveLine,
		quietLine = line?.quiet == true,
		progress = item.progress,
		watched = item.showsWatched,
		unwatchedCount = item.unwatchedBadge,
		description = remember(item, line) {
			if (line == null) item.spokenSummary else "${item.spokenSummary}, ${line.text}"
		},
		onClick = onClick,
		onLongClick = onLongClick,
		onLongClickLabel = onLongClickLabel,
		opens = if (onClick == null) null else item.ratingKey,
		showsTitle = labels.showsTitle,
	)
}

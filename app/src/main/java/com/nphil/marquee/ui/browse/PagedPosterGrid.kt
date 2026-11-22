package com.nphil.marquee.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.nphil.marquee.core.Paged
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.ui.components.pageScroll
import com.nphil.marquee.ui.theme.Space
import kotlinx.coroutines.flow.filterNotNull

/**
 * A poster grid over a paged list, with the states around it ([PagedContent]). It fills the width in as many columns as
 * [posterMinWidth] allows; [poster] draws one cell at the width it is given. [onNearEnd] hears the key of the last
 * poster on screen, which is what a [com.nphil.marquee.core.PagedLoader] needs to load the next page in time. A screen
 * that puts something over the end edge, such as a jump rail, widens [endInset].
 */
@Composable
fun PagedPosterGrid(
	paged: Paged<PlexItem>,
	serverName: String,
	onRetry: () -> Unit,
	onSignIn: () -> Unit,
	onNearEnd: (key: String) -> Unit,
	modifier: Modifier = Modifier,
	state: LazyGridState = rememberLazyGridState(),
	startInset: Dp = Space.inset,
	endInset: Dp = Space.inset,
	banner: Boolean = true,
	empty: @Composable () -> Unit,
	poster: @Composable (item: PlexItem, width: Dp) -> Unit,
) {
	BoxWithConstraints(modifier) {
		val metrics = PosterGridMetrics.of(maxWidth - startInset - endInset, LocalDensity.current.fontScale)
		PagedContent(
			paged = paged,
			serverName = serverName,
			onRetry = onRetry,
			onSignIn = onSignIn,
			banner = banner,
			skeleton = { GridSkeleton(metrics, Modifier.padding(start = startInset, top = Space.s3)) },
			empty = empty,
		) {
			LazyVerticalGrid(
				columns = GridCells.Fixed(metrics.columns),
				modifier = Modifier.pageScroll(state, end = endInset - minOf(Space.s2, endInset)),
				state = state,
				contentPadding = PaddingValues(start = startInset, end = endInset, top = Space.s3, bottom = Space.s7),
				horizontalArrangement = Arrangement.spacedBy(metrics.gutter),
				verticalArrangement = Arrangement.spacedBy(Space.s5),
			) {
				items(paged.items, key = { it.ratingKey }, contentType = { "poster" }) { item -> poster(item, metrics.cellWidth) }
			}
			NearEnd(state, paged.items.size, onNearEnd)
		}
	}
}

/**
 * Reports the last poster on screen as the grid scrolls, and again when a page lands: a page that was asked for while
 * the reader already sat at the end must be asked for once more.
 */
@Composable
private fun NearEnd(state: LazyGridState, count: Int, onNearEnd: (String) -> Unit) {
	val report by rememberUpdatedState(onNearEnd)
	LaunchedEffect(state, count) {
		snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String }
			.filterNotNull()
			.collect { report(it) }
	}
}

package com.nphil.marquee.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.components.cardHeight
import com.nphil.marquee.ui.components.posterHeight
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

// Static fills in the shape of what is loading (DESIGN §11), swapped for content at once when it lands. They stay
// still on purpose: nothing animates while the panel waits.

@Composable
private fun Fill(modifier: Modifier, radius: Dp) {
	Box(modifier.clip(RoundedCornerShape(radius)).background(lucent.trackOff))
}

@Composable
private fun Bar(width: Dp, height: Dp) {
	Box(Modifier.width(width).height(height).clip(CircleShape).background(lucent.trackOff))
}

/** A row that runs past the screen's edge: its children are laid out unbounded and clipped there, so any width is filled. */
@Composable
private fun Run(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	Row(
		modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = false),
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
		verticalAlignment = Alignment.Top,
	) { content() }
}

/** A shelf header bar over a row of poster fills. */
@Composable
fun ShelfSkeleton(posterWidth: Dp, modifier: Modifier = Modifier) {
	Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		Box(Modifier.padding(horizontal = Space.inset)) { Bar(160.dp, 20.dp) }
		Run(Modifier.padding(start = Space.inset)) {
			repeat(12) { Fill(Modifier.size(posterWidth, posterHeight(posterWidth)), Radius.tile) }
		}
	}
}

/** Continue Watching's two 16:9 cards under a header bar. */
@Composable
fun ContinueSkeleton(cardWidth: Dp, modifier: Modifier = Modifier) {
	Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		Box(Modifier.padding(horizontal = Space.inset)) { Bar(160.dp, 20.dp) }
		Run(Modifier.padding(start = Space.inset)) {
			repeat(4) { Fill(Modifier.size(cardWidth, cardHeight(cardWidth)), Radius.card) }
		}
	}
}

/** Four screens' worth of poster fills in grid columns. */
@Composable
fun GridSkeleton(metrics: PosterGridMetrics, modifier: Modifier = Modifier) {
	Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		repeat(4) {
			Row(horizontalArrangement = Arrangement.spacedBy(metrics.gutter)) {
				repeat(metrics.columns) {
					Fill(Modifier.size(metrics.cellWidth, posterHeight(metrics.cellWidth)), Radius.tile)
				}
			}
		}
	}
}

/** Rows with a small art fill and two text bars. */
@Composable
fun RowsSkeleton(modifier: Modifier = Modifier, artWidth: Dp = 56.dp, artHeight: Dp = 84.dp) {
	Column(
		modifier.padding(horizontal = Space.inset).clearAndSetSemantics {},
		verticalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		repeat(5) {
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
				Fill(Modifier.size(artWidth, artHeight), Radius.control)
				Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
					Bar(160.dp, 14.dp)
					Bar(96.dp, 12.dp)
				}
			}
		}
	}
}

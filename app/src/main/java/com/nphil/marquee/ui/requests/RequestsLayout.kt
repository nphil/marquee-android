package com.nphil.marquee.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.components.posterHeight
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** Wide enough for the filter panel to sit beside the list and for a title page to run in two panes. */
internal val WideBreakpoint = 840.dp

/** A poster's width: larger where the window has room, in whole dp so the art is asked of TMDB at the pixels it takes. */
internal fun posterWidth(wide: Boolean): Dp = if (wide) 152.dp else 112.dp

/** The screen's own start and end padding, which a display cutout or a side bar takes; Lucent's inset is the lists' own. */
@Composable
internal fun PaddingValues.sides(): Modifier {
	val direction = LocalLayoutDirection.current
	return Modifier.padding(start = calculateLeftPadding(direction), end = calculateRightPadding(direction))
}

/** A poster's place while its titles load: the quiet track colour at the poster's size. */
@Composable
internal fun PosterPlaceholder(width: Dp, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(if (width >= 96.dp) Radius.tile else Radius.control)
	Box(modifier.size(width, posterHeight(width)).clip(shape).background(lucent.trackOff))
}

/** Shelves of placeholders for Discover's first load. */
@Composable
internal fun ShelvesSkeleton(width: Dp, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.semantics { contentDescription = "Loading" },
		verticalArrangement = Arrangement.spacedBy(Space.s5),
	) {
		repeat(3) {
			Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				Box(Modifier.padding(start = Space.inset).width(160.dp).height(24.dp).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
				Row(
					modifier = Modifier.fillMaxWidth().clipToBounds().padding(horizontal = Space.inset),
					horizontalArrangement = Arrangement.spacedBy(Space.s3),
				) {
					repeat(8) { PosterPlaceholder(width) }
				}
			}
		}
	}
}

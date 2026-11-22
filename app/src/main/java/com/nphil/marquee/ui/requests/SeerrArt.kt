package com.nphil.marquee.ui.requests

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.nphil.marquee.R
import com.nphil.marquee.core.SeerrBadge
import com.nphil.marquee.core.SeerrTitle
import com.nphil.marquee.core.TmdbImages
import com.nphil.marquee.ui.components.posterHeight
import com.nphil.marquee.ui.components.pressable
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.ceil

/**
 * A TMDB image at [widthDp] by [heightDp], asked of TMDB's image server at the smallest size that covers the width
 * in pixels. TMDB needs no sign-in, so no headers go with it. Art that is missing, or not loaded yet, shows the quiet
 * track colour with a film glyph.
 */
@Composable
fun TmdbArt(
	path: String?,
	widthDp: Dp,
	heightDp: Dp,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
) {
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val widthPx = ceil(widthDp.value * density).toInt()
	val heightPx = ceil(heightDp.value * density).toInt()
	val request = remember(path, widthPx, heightPx) {
		val url = TmdbImages.url(path, widthPx) ?: return@remember null
		val key = "tmdb|$url|${widthPx}x$heightPx"
		ImageRequest.Builder(context)
			.data(url)
			.size(Size(widthPx, heightPx))
			.precision(Precision.INEXACT)
			.memoryCacheKey(key)
			.diskCacheKey(key)
			.crossfade(150)
			.build()
	}
	Box(modifier.size(widthDp, heightDp).background(lucent.trackOff), contentAlignment = Alignment.Center) {
		if (request == null) {
			if (minOf(widthDp, heightDp) >= 48.dp) {
				Icon(painterResource(R.drawable.ic_movie), contentDescription = null, tint = lucent.ink3, modifier = Modifier.size(24.dp))
			}
		} else {
			AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
		}
	}
}

/** The glyph for a status Core named; an unknown name has none. */
@DrawableRes
internal fun badgeIcon(name: String): Int? = when (name) {
	"available" -> R.drawable.ic_check
	"partiallyAvailable" -> R.drawable.ic_contrast
	"processing" -> R.drawable.ic_download
	"approved" -> R.drawable.ic_schedule
	"pending" -> R.drawable.ic_hourglass_empty
	"declined" -> R.drawable.ic_close
	"failed" -> R.drawable.ic_error
	"blocklisted" -> R.drawable.ic_block
	else -> null
}

/** What colour a status's glyph takes: done is positive, waiting is a warning, refused or broken is danger. */
@Composable
internal fun badgeTint(name: String): Color = when (name) {
	"available", "partiallyAvailable" -> lucent.positive
	"processing", "approved" -> lucent.info
	"pending" -> lucent.warning
	"declined", "failed", "blocklisted" -> lucent.danger
	else -> lucent.ink2
}

/** A status on a poster's top trailing corner: a solid canvas disc with the glyph, never glass. Decoration; the card's description says it. */
@Composable
fun BoxScope.StatusDisc(badge: SeerrBadge?) {
	val icon = badge?.let { badgeIcon(it.name) } ?: return
	Box(
		modifier = Modifier
			.align(Alignment.TopEnd)
			.padding(Space.s2)
			.size(Space.s6)
			.clip(CircleShape)
			.background(lucent.canvas),
		contentAlignment = Alignment.Center,
	) {
		Icon(painterResource(icon), contentDescription = null, tint = badgeTint(badge.name), modifier = Modifier.size(16.dp))
	}
}

/** A status as a glyph and its words in one line. */
@Composable
fun StatusLine(badge: SeerrBadge, suffix: String? = null, prefix: String? = null, modifier: Modifier = Modifier) {
	Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
		badgeIcon(badge.name)?.let {
			Icon(painterResource(it), contentDescription = null, tint = badgeTint(badge.name), modifier = Modifier.size(18.dp))
		}
		Text(
			listOfNotNull(prefix, badge.title, suffix).joinToString(" · "),
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink,
		)
	}
}

/**
 * A title in a shelf or grid: 2:3 art with its status, then the name and the year. One line of description for
 * screen readers covers all of it.
 */
@Composable
fun SeerrPoster(
	title: SeerrTitle,
	width: Dp,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	val shape = RoundedCornerShape(if (width >= 96.dp) Radius.tile else Radius.control)
	val spoken = listOfNotNull(title.name, title.year?.toString(), title.badge?.title).joinToString(", ")
	Column(
		modifier = modifier
			.width(width)
			.pressable(onClick)
			.semantics(mergeDescendants = true) { contentDescription = spoken },
		verticalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		Box {
			TmdbArt(title.posterPath, width, posterHeight(width), Modifier.clip(shape))
			StatusDisc(title.badge)
		}
		Column(Modifier.clearAndSetSemantics { }) {
			Text(
				title.name,
				style = MaterialTheme.typography.bodySmall,
				fontWeight = FontWeight.SemiBold,
				color = lucent.ink,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(title.year?.toString() ?: " ", style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 1)
		}
	}
}

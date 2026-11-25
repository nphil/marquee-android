package com.nphil.marquee.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nphil.marquee.R
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The main action of a screen: a filled pill across the content width. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = Size.target)) {
		Text(text, style = MaterialTheme.typography.labelLarge)
	}
}

/** A secondary action as accent text. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	TextButton(
		onClick = onClick,
		modifier = modifier.heightIn(min = Size.target),
		colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
	) {
		Text(text, style = MaterialTheme.typography.bodyLarge)
	}
}

/** A screen with nothing to list: a glyph, what is missing and why, and what to do about it. */
@Composable
fun EmptyState(
	@DrawableRes icon: Int,
	title: String,
	message: String,
	modifier: Modifier = Modifier,
	action: (@Composable () -> Unit)? = null,
) {
	Column(
		modifier = modifier.widthIn(max = 420.dp).padding(Space.inset),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Icon(painterResource(icon), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(40.dp))
		Text(title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, textAlign = TextAlign.Center)
		Text(message, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2, textAlign = TextAlign.Center)
		action?.invoke()
	}
}

/** The action under an [EmptyState] that loads again. */
@Composable
fun RetryButton(text: String, onClick: () -> Unit) {
	FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = Size.target)) {
		Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
		Spacer(Modifier.width(Space.s2))
		Text(text, style = MaterialTheme.typography.labelLarge)
	}
}

/**
 * One row of a group: rows sit on sheet with a 2 dp gap, the group's outer corners at the card radius and
 * the corners between rows tight, as Android's own settings lists are drawn. [surface] is how much of the row's
 * background shows, 1 for all of it; the content is drawn in full either way.
 */
@Composable
fun GroupRow(
	index: Int,
	count: Int,
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	onClick: (() -> Unit)? = null,
	surface: Float = 1f,
	content: @Composable RowScope.() -> Unit,
) {
	val top = if (index == 0) Radius.card else Space.s1
	val bottom = if (index == count - 1) Radius.card else Space.s1
	val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
	Row(
		modifier = modifier
			.fillMaxWidth()
			.clip(shape)
			.background((if (selected) MaterialTheme.colorScheme.secondaryContainer else lucent.sheet).let { it.copy(alpha = it.alpha * surface) })
			.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
			.heightIn(min = Size.rowMin)
			.padding(horizontal = Space.s4, vertical = Space.s3),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
		content = content,
	)
}

/** The gap between the rows of a group. */
val GroupGap = 2.dp

/** A row that shows its choice and opens a menu of the [options]. */
@Composable
fun <T> ChoiceRow(label: String, value: T, options: List<T>, title: (T) -> String, index: Int, count: Int, onChange: (T) -> Unit) {
	var open by remember { mutableStateOf(false) }
	GroupRow(index, count, onClick = { open = true }) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Box {
			Text(title(value), style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
			DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
				options.forEach { option ->
					DropdownMenuItem(
						text = { Text(title(option)) },
						onClick = {
							open = false
							onChange(option)
						},
					)
				}
			}
		}
	}
}

/** A failure said in a line: the danger glyph, then the words in ink so they read on any theme. */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = lucent.danger, modifier = Modifier.size(20.dp))
		Text(text, style = MaterialTheme.typography.labelLarge, color = lucent.ink)
	}
}

/** A profile picture, or its initial on trackOff, inside an edgeRaised ring. */
@Composable
fun Avatar(user: PlexHomeUser?, size: Dp, modifier: Modifier = Modifier) {
	val theme = lucent
	Box(
		modifier = modifier
			.size(size)
			.clip(CircleShape)
			.background(theme.trackOff, CircleShape)
			.border(BorderStroke(1.dp, theme.edgeRaised), CircleShape),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = user?.title?.firstOrNull()?.uppercase() ?: "",
			style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize * (size.value / 56f)),
			color = theme.ink,
		)
		if (user?.thumb != null) {
			AsyncImage(model = user.thumb, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
		}
	}
}

/**
 * The Marquee mark drawn in the theme's colours: a sign board ringed by 22 bulbs around the M, as the
 * iPhone app draws it.
 */
@Composable
fun MarqueeMark(modifier: Modifier = Modifier) {
	val theme = lucent
	Spacer(
		modifier.aspectRatio(740f / 490f).drawWithCache {
			val unit = size.width / 740f
			val corner = CornerRadius(118 * unit)
			val rim = max(1f, 4 * unit)
			val inset = 56 * unit
			val bulbs = bulbs(Rect(inset, inset, size.width - inset, size.height - inset), 62 * unit)
			val letter = Path().apply {
				moveTo(257 * unit, 334 * unit)
				lineTo(257 * unit, 156 * unit)
				lineTo(370 * unit, 269.9f * unit)
				lineTo(483 * unit, 156 * unit)
				lineTo(483 * unit, 334 * unit)
			}
			val letterStroke = Stroke(width = 54 * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
			onDrawBehind {
				drawRoundRect(theme.glassRaised, cornerRadius = corner)
				drawRoundRect(
					theme.edgeRaised,
					topLeft = Offset(rim / 2, rim / 2),
					size = GeometrySize(size.width - rim, size.height - rim),
					cornerRadius = corner,
					style = Stroke(rim),
				)
				bulbs.forEach { drawCircle(theme.ink2, radius = 19 * unit, center = it) }
				drawPath(letter, theme.ink, style = letterStroke)
			}
		}
	)
}

/** 22 points spaced evenly along a rounded rectangle, starting at the middle of the top edge. */
private fun bulbs(rect: Rect, r: Float): List<Offset> {
	val straightX = rect.width - 2 * r
	val straightY = rect.height - 2 * r
	val arc = (PI / 2).toFloat() * r
	val perimeter = 2 * straightX + 2 * straightY + 4 * arc
	val corners = listOf(
		Offset(rect.right - r, rect.top + r),
		Offset(rect.right - r, rect.bottom - r),
		Offset(rect.left + r, rect.bottom - r),
		Offset(rect.left + r, rect.top + r),
	)
	val starts = listOf(
		Offset(rect.left + r, rect.top),
		Offset(rect.right, rect.top + r),
		Offset(rect.right - r, rect.bottom),
		Offset(rect.left, rect.bottom - r),
	)
	val directions = listOf(Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f), Offset(0f, -1f))
	val edges = listOf(straightX, straightY, straightX, straightY)
	fun point(distance: Float): Offset {
		var s = distance % perimeter
		for (side in 0..3) {
			if (s <= edges[side]) return starts[side] + directions[side] * s
			s -= edges[side]
			if (s <= arc) {
				// A quarter turn around the corner, clockwise from the top.
				val angle = -PI.toFloat() / 2 + side * PI.toFloat() / 2 + s / r
				return corners[side] + Offset(r * cos(angle), r * sin(angle))
			}
			s -= arc
		}
		return starts[0]
	}
	return List(22) { point(straightX / 2 + it * perimeter / 22) }
}

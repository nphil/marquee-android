package com.nphil.marquee.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nphil.marquee.core.BadgeRange
import com.nphil.marquee.core.BadgeSound
import com.nphil.marquee.core.FormatBadge
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay

private val MARK_HEIGHT = 14.dp

/** How long the badge stays up. A bright mark does not sit on the panel any longer than that. */
private const val BADGE_DWELL_MS = 1_000L

/**
 * Shows the title's [FormatBadge] for one second once the controls have faded, then marks it taken so it does not
 * come back for the same title. If the controls return first it goes at once. Draws nothing without a pending badge.
 */
@Composable
fun FormatBadgeHost(model: PlayerModel, controlsVisible: Boolean, modifier: Modifier = Modifier) {
	val pending = model.pendingBadge
	var on by remember { mutableStateOf(false) }
	// The last badge stays drawn while it fades out.
	val last = remember { arrayOfNulls<FormatBadge>(1) }
	if (pending != null) last[0] = pending
	LaunchedEffect(pending, controlsVisible) {
		if (pending == null) return@LaunchedEffect
		if (pending.isEmpty) {
			model.badgeTaken()
			return@LaunchedEffect
		}
		if (controlsVisible) {
			if (on) {
				on = false
				model.badgeTaken()
			}
			return@LaunchedEffect
		}
		on = true
		delay(BADGE_DWELL_MS)
		on = false
		model.badgeTaken()
	}
	AnimatedVisibility(visible = on && pending != null, enter = EnterTransition.None, exit = fadeOut(tween(150)), modifier = modifier) {
		(pending ?: last[0])?.let { FormatBadgeView(it) }
	}
}

/**
 * What the picture and sound are, as a capsule: the resolution, then the range and the sound when the file has them.
 * It reveals itself with a short fade and scale and one pass of a faint sheen. It draws in the theme's ink on its
 * scrim, so it is never brighter than the theme's own text.
 */
@Composable
fun FormatBadgeView(badge: FormatBadge, modifier: Modifier = Modifier) {
	val reveal = remember { Animatable(0f) }
	val sheen = remember { Animatable(0f) }
	LaunchedEffect(Unit) { reveal.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing)) }
	LaunchedEffect(Unit) { sheen.animateTo(1f, tween(durationMillis = 650, delayMillis = 180, easing = FastOutSlowInEasing)) }
	val ink = lucent.ink
	val shape = CircleShape
	Row(
		modifier = modifier
			.graphicsLayer {
				alpha = reveal.value
				val scale = 0.92f + 0.08f * reveal.value
				scaleX = scale
				scaleY = scale
			}
			.semantics { contentDescription = badge.spoken }
			.clip(shape)
			.background(lucent.scrim, shape)
			.border(1.dp, lucent.edge, shape)
			.drawWithContent {
				drawContent()
				// A diagonal band of ink at 12% sweeps across once; the capsule's clip keeps it inside.
				val x = -size.height + (size.width + size.height) * sheen.value
				if (sheen.value in 0.01f..0.99f) {
					drawRect(
						Brush.linearGradient(
							colors = listOf(Color.Transparent, ink.copy(alpha = 0.12f), Color.Transparent),
							start = Offset(x, 0f),
							end = Offset(x + size.height, size.height),
						),
					)
				}
			}
			.padding(horizontal = Space.s3, vertical = Space.s1 + 2.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		// The capsule's own description says it all; its parts are not read again one by one.
		badge.resolution?.let { MarkText(it, ink, bold = true) }
		badge.range?.let { range ->
			Divider()
			when (range) {
				BadgeRange.DolbyVision -> Canvas(Modifier.size(width = 20.dp, height = MARK_HEIGHT)) { drawLens(ink) }
				else -> MarkText(range.words, ink)
			}
		}
		badge.sound?.let { sound ->
			Divider()
			when (sound) {
				BadgeSound.Atmos -> Canvas(Modifier.size(width = 22.dp, height = MARK_HEIGHT)) { drawDome(ink) }
				else -> MarkText(sound.words, ink)
			}
		}
	}
}

@Composable
private fun Divider() {
	Box(Modifier.width(1.dp).height(12.dp).background(lucent.edge))
}

@Composable
private fun MarkText(text: String, ink: Color, bold: Boolean = false) {
	val style = if (bold) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.labelMedium
	Text(text, style = style, color = ink, modifier = Modifier.clearAndSetSemantics { })
}

/** Dolby Vision: a lens, its ring and the pupil inside. */
private fun DrawScope.drawLens(color: Color) {
	val stroke = 1.6.dp.toPx()
	drawOval(
		color,
		topLeft = Offset(stroke / 2, stroke / 2),
		size = Size(size.width - stroke, size.height - stroke),
		style = Stroke(stroke),
	)
	drawCircle(color, radius = size.height * 0.24f, center = center)
}

/** Dolby Atmos: a dome with two arcs of sound opening over it. */
private fun DrawScope.drawDome(color: Color) {
	val stroke = 1.5.dp.toPx()
	val base = Offset(size.width / 2, size.height)
	fun arc(radius: Float, filled: Boolean) {
		drawArc(
			color,
			startAngle = 180f,
			sweepAngle = 180f,
			useCenter = filled,
			topLeft = Offset(base.x - radius, base.y - radius),
			size = Size(radius * 2, radius * 2),
			style = if (filled) Fill else Stroke(stroke, cap = StrokeCap.Round),
		)
	}
	arc(size.height * 0.42f, filled = true)
	arc(size.height * 0.72f, filled = false)
	arc(size.height * 0.98f - stroke / 2, filled = false)
}

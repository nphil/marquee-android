package com.nphil.marquee.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space

private const val PRESSED_SCALE = 0.92f

/** A soft dark shadow behind light text, so it reads on a bright picture where the scrim is thin. */
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 6f)

/** Digits of equal width, so a running clock does not shift the bar beside it. */
@Composable
internal fun clockStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", shadow = TextShadow)

internal fun shadowed(style: TextStyle): TextStyle = style.copy(shadow = TextShadow)

/**
 * A glass disc around [content]: a dark disc with a hairline edge, so a glyph reads on any picture. It shrinks a
 * little while pressed and draws a ring when it has the keyboard's or the pad's focus. [solid] is the play button: a
 * light disc, which also changes [shape] with what it does.
 */
@Composable
internal fun GlassDisc(
	description: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	size: Dp = Size.target,
	solid: Boolean = false,
	shape: Shape = CircleShape,
	content: @Composable BoxScope.() -> Unit,
) {
	val source = remember { MutableInteractionSource() }
	val pressed by source.collectIsPressedAsState()
	val focused by source.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
	val fill = when {
		solid -> if (pressed) PlayerInk.solid.copy(alpha = 0.84f) else PlayerInk.solid
		pressed -> PlayerInk.glassPressed
		else -> PlayerInk.glass
	}
	Box(
		modifier
			.size(size)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clip(shape)
			.background(fill)
			.border(if (focused) 2.dp else 1.dp, if (focused) PlayerInk.ink else PlayerInk.edge, shape)
			.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
			.semantics { contentDescription = description },
		contentAlignment = Alignment.Center,
		content = content,
	)
}

/** A [GlassDisc] with one glyph. */
@Composable
internal fun GlassButton(
	@DrawableRes icon: Int,
	description: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	size: Dp = Size.target,
	glyph: Dp = 24.dp,
	tint: Color = PlayerInk.ink,
) {
	GlassDisc(description, onClick, modifier, size) {
		Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(glyph))
	}
}

/**
 * A capsule with a label and, optionally, a glyph: glass by default, or filled with [fill] (the lifted accent for the
 * one action a screen is asking for) with [content] on it. [trailing] draws after the label, for the countdown ring.
 */
@Composable
internal fun GlassPill(
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	fill: Color? = null,
	content: Color = PlayerInk.ink,
	trailing: @Composable (() -> Unit)? = null,
) {
	val source = remember { MutableInteractionSource() }
	val pressed by source.collectIsPressedAsState()
	val focused by source.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
	val background = when {
		fill != null -> if (pressed) fill.copy(alpha = 0.82f) else fill
		pressed -> PlayerInk.glassPressed
		else -> PlayerInk.glass
	}
	Row(
		modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clip(CircleShape)
			.background(background)
			.border(if (focused) 2.dp else 1.dp, if (focused) PlayerInk.ink else if (fill != null) Color.Transparent else PlayerInk.edge, CircleShape)
			.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
			.defaultMinSize(minHeight = Size.target)
			.padding(horizontal = Space.s4),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
	) {
		if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
		Text(label, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
		trailing?.invoke()
	}
}

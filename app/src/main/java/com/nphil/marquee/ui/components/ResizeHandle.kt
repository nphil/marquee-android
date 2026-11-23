package com.nphil.marquee.ui.components

import android.view.PointerIcon as SystemPointerIcon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.abs

private val PillThickness = Space.s1
private val PillLength = Space.s8
private val PillHeldLength = 72.dp
private val PillInset = 3.dp
private val EdgeThickness = 2.dp

// Faint at rest, so the handle hints at itself without being a bright thing that stays in one place.
private const val RestAlpha = 0.5f
private const val EdgeAlpha = 0.6f

/**
 * The handle on the edge of something the person can resize: a thin pill that is faint at rest and brightens to the
 * accent, growing a little, while a finger or pointer is on it. A pointer sees the resize cursor, and an assistive
 * service gets a slider named [label] over [range].
 *
 * Size and place it with [modifier]; the pill sits [atEnd] of the box, at the right in a horizontal handle (the left in
 * a right-to-left layout) and at the bottom in a vertical one. [orientation] is the axis it is dragged along. A drag
 * has to travel a touch slop along that axis before it takes hold, and a drag that goes across it first is left to
 * whatever is underneath, so a tap or a swipe on a bar that carries a handle still reaches the bar.
 *
 * [onStart], [onDrag] and [onEnd] bracket one drag. [onDrag] gets the travel in px since the finger went down,
 * positive toward the end edge, and [onEnd] the speed in px/s with the same sign. [onAdjust] is the assistive service
 * asking for [value] to become another number in [range].
 */
@Composable
fun ResizeHandle(
	orientation: Orientation,
	label: String,
	value: () -> Float,
	range: () -> ClosedFloatingPointRange<Float>,
	onAdjust: (Float) -> Unit,
	onStart: () -> Unit,
	onDrag: (travelled: Float) -> Unit,
	onEnd: (velocity: Float) -> Unit,
	modifier: Modifier = Modifier,
	atEnd: Boolean = true,
) {
	val theme = lucent
	val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
	val horizontal = orientation == Orientation.Horizontal
	val started by rememberUpdatedState(onStart)
	val dragged by rememberUpdatedState(onDrag)
	val ended by rememberUpdatedState(onEnd)
	val adjusted by rememberUpdatedState(onAdjust)
	val hover = remember { MutableInteractionSource() }
	val hovered by hover.collectIsHoveredAsState()
	var held by remember { mutableStateOf(false) }
	// Read in draw only, so the glow never recomposes the handle's parent.
	val glow = animateFloatAsState(
		targetValue = if (hovered || held) 1f else 0f,
		animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
		label = "resize-handle",
	)
	val cursor = PointerIcon(
		if (horizontal) SystemPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW else SystemPointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
	)
	Box(
		modifier
			.hoverable(hover)
			.pointerHoverIcon(cursor)
			.pointerInput(horizontal, rtl) {
				val slop = viewConfiguration.touchSlop
				val toward = if (horizontal && rtl) -1f else 1f
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val tracker = VelocityTracker()
					var along = 0f
					var across = 0f
					var grabbed = false
					while (true) {
						val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
						if (change.isConsumed) break
						// Positions are local to a handle that moves with what it resizes, so the travel is the sum of the changes.
						val moved = change.positionChange()
						along += toward * if (horizontal) moved.x else moved.y
						across += if (horizontal) moved.y else moved.x
						tracker.addPosition(change.uptimeMillis, Offset(along, 0f))
						if (!change.pressed) break
						if (!grabbed) {
							if (abs(across) >= slop && abs(across) > abs(along)) break
							if (abs(along) < slop) continue
							grabbed = true
							held = true
							started()
						}
						dragged(along)
						change.consume()
					}
					if (grabbed) {
						held = false
						ended(tracker.calculateVelocity().x)
					}
				}
			}
			.semantics {
				contentDescription = label
				val span = range()
				progressBarRangeInfo = ProgressBarRangeInfo(value().coerceIn(span), span)
				setProgress { target ->
					adjusted(target)
					true
				}
			}
			.drawBehind {
				val lit = glow.value
				val thickness = PillThickness.toPx()
				val length = lerp(PillLength.toPx(), PillHeldLength.toPx(), lit)
				val inset = PillInset.toPx()
				val color = lerp(theme.ink3.copy(alpha = RestAlpha), theme.accent, lit)
				val radius = CornerRadius(thickness / 2)
				if (horizontal) {
					val right = atEnd != rtl
					val x = if (right) size.width - inset - thickness else inset
					drawRoundRect(color, Offset(x, (size.height - length) / 2), Size(thickness, length), radius)
					if (lit > 0f) {
						val edge = EdgeThickness.toPx()
						drawRect(theme.accent.copy(alpha = EdgeAlpha * lit), Offset(if (right) size.width - edge else 0f, 0f), Size(edge, size.height))
					}
				} else {
					val y = if (atEnd) size.height - inset - thickness else inset
					drawRoundRect(color, Offset((size.width - length) / 2, y), Size(length, thickness), radius)
				}
			},
	)
}

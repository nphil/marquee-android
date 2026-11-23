package com.nphil.marquee.ui.tvremote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity

/**
 * Drag measured in window coordinates. What a drag moves is usually the very node that carries the gesture (the orb,
 * the panel header), and a node's own coordinates shift under the finger as it follows, so local deltas would wobble.
 * [onDrag] gets the movement in px once the touch slop is passed, [onEnd] the release velocity in px per second.
 */
@Composable
internal fun Modifier.rootDrag(
	enabled: Boolean = true,
	onStart: () -> Unit = {},
	onDrag: (Offset) -> Unit,
	onEnd: (Velocity) -> Unit = {},
): Modifier {
	val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
	val start by rememberUpdatedState(onStart)
	val drag by rememberUpdatedState(onDrag)
	val end by rememberUpdatedState(onEnd)
	return onGloballyPositioned { holder[0] = it }.pointerInput(enabled) {
		if (!enabled) return@pointerInput
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			fun root(local: Offset) = holder[0]?.localToRoot(local) ?: local
			val tracker = VelocityTracker()
			var last = root(down.position)
			tracker.addPosition(down.uptimeMillis, last)
			var total = Offset.Zero
			var dragging = false
			while (true) {
				val event = awaitPointerEvent()
				val change = event.changes.firstOrNull { it.id == down.id }
				if (change == null || !change.pressed) {
					if (dragging) end(if (change == null) Velocity.Zero else tracker.calculateVelocity())
					break
				}
				val now = root(change.position)
				tracker.addPosition(change.uptimeMillis, now)
				var delta = now - last
				last = now
				total += delta
				if (!dragging && total.getDistance() >= viewConfiguration.touchSlop) {
					dragging = true
					delta = total
					start()
				}
				if (dragging) {
					change.consume()
					drag(delta)
				}
			}
		}
	}
}

/**
 * Runs [onHold] when a touch rests for [millis] without leaving the touch slop. It watches the final pass, so a
 * control that takes the touch itself never starts it: only an empty area does.
 */
internal fun Modifier.onHold(millis: Long, onHold: () -> Unit): Modifier = pointerInput(millis) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
		val settled = withTimeoutOrNull(millis) {
			var resting = true
			while (resting) {
				val event = awaitPointerEvent(PointerEventPass.Final)
				val change = event.changes.firstOrNull { it.id == down.id }
				resting = change != null && change.pressed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
			}
			true
		}
		if (settled == null) onHold()
	}
}

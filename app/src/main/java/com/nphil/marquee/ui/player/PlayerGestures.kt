package com.nphil.marquee.ui.player

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where on the picture a touch lands, the way Core splits it for double-taps: a third at each side and the middle. */
internal enum class Zone { Start, Middle, End }

internal fun zoneOf(x: Float, width: Float): Zone = when {
	x < width / 3f -> Zone.Start
	x >= width * 2f / 3f -> Zone.End
	else -> Zone.Middle
}

/** The left and right edge of [zone] on a picture [width] wide. */
internal fun zoneSpan(zone: Zone, width: Float): ClosedFloatingPointRange<Float> = when (zone) {
	Zone.Start -> 0f..width / 3f
	Zone.Middle -> width / 3f..width * 2f / 3f
	Zone.End -> width * 2f / 3f..width
}

/**
 * What a vertical swipe does, by the third of the picture it begins in: the left third sets the brightness, the right
 * third the volume, and in the middle it pulls the player down into the bar.
 */
internal enum class SwipeKind { Brightness, Volume, Minimise }

internal fun swipeKind(x: Float, width: Float): SwipeKind = when (zoneOf(x, width)) {
	Zone.Start -> SwipeKind.Brightness
	Zone.Middle -> SwipeKind.Minimise
	Zone.End -> SwipeKind.Volume
}

/** Sweeping this share of the picture's height takes a level from nothing to full. */
private const val SWEEP = 0.75f

/** The brightness never goes lower than this: at nothing some screens switch their backlight off and the picture is lost. */
internal const val BRIGHTNESS_FLOOR = 0.02f

/** The level after a finger has moved [dy] pixels down (up is negative) from where it started at [start], 0 to 1. */
internal fun swipeLevel(start: Float, dy: Float, height: Float): Float =
	if (height <= 0f) start else (start - dy / (height * SWEEP)).coerceIn(0f, 1f)

/** The device's volume step for [level], from 0 to [max]. */
internal fun volumeStep(level: Float, max: Int): Int = (level * max).roundToInt().coerceIn(0, max)

/** Which side of the picture a run of double-taps is skipping toward. */
internal enum class SkipSide { Back, Forward }

/**
 * The side after the skipped total went from [before] to [after]: forward when it grew, back when it shrank. When it
 * did not change the side stays as it was, so a ripple that is fading does not swap sides.
 */
internal fun skipSide(before: Int, after: Int, last: SkipSide): SkipSide = when {
	after > before -> SkipSide.Forward
	after < before -> SkipSide.Back
	else -> last
}

/** The running total as the ripple shows it, with a real minus sign. */
internal fun skipLabel(total: Int): String = when {
	total > 0 -> "+$total s"
	total < 0 -> "\u2212${-total} s"
	else -> "0 s"
}

/** Pinching in or out past these ratios picks the other aspect; between them the picture stays as it is. */
private const val PINCH_OUT = 1.12f
private const val PINCH_IN = 0.9f

/** The aspect a pinch of [zoom] (1 = no change) asks for: spreading fills the screen, pinching shows the whole picture. */
internal fun pinchAspect(zoom: Float): PlayerAspect? = when {
	zoom >= PINCH_OUT -> PlayerAspect.Fill
	zoom <= PINCH_IN -> PlayerAspect.Fit
	else -> null
}

/** A drag across the picture covers this much of the title at most, so a long film is not skipped through in one swipe. */
private const val PICTURE_SCRUB_SECONDS = 120.0

/**
 * Where the playhead is after a horizontal drag of [dx] pixels on something [width] wide. A full width covers [span]
 * seconds, kept inside the title's [duration].
 */
internal fun scrubbed(from: Double, dx: Float, width: Float, span: Double, duration: Double): Double =
	if (width <= 0f) from else (from + dx / width * span).coerceIn(0.0, duration)

/** The seconds a full-width drag on the picture covers for a title [duration] seconds long. */
internal fun pictureSpan(duration: Double): Double = minOf(duration, PICTURE_SCRUB_SECONDS)

/** The picture's gestures as plain callbacks, so the detector holds no player state of its own. */
@Stable
internal class PictureActions(
	val locked: () -> Boolean,
	val canHold: () -> Boolean,
	val tap: (x: Float, width: Float) -> Unit,
	val lockedTap: () -> Unit,
	val hold: (on: Boolean) -> Unit,
	val swipeBegan: (SwipeKind) -> Unit,
	val swiped: (kind: SwipeKind, dy: Float, height: Float) -> Unit,
	val minimiseBegan: () -> Unit,
	/** [dy] is how far the finger has gone down since the pull began, up being negative. */
	val minimised: (dy: Float) -> Unit,
	/** [velocityY] is in pixels per second, positive down. */
	val minimiseEnded: (velocityY: Float) -> Unit,
	val scrubBegan: () -> Unit,
	val scrubbed: (dx: Float, width: Float) -> Unit,
	val scrubEnded: () -> Unit,
	val pinched: (zoom: Float) -> Unit,
)

private enum class Mode { Undecided, Idle, Hold, Swipe, Scrub, Pinch }

/**
 * Tells what a touch on the picture is. Up and let go quickly is a tap, and Core decides what a tap does. Held in place
 * is the hold for double speed. Moved up or down is a brightness or volume swipe by the third it began in, and in the
 * middle third it pulls the player down into the bar; moved across is a scrub. A second finger makes it a pinch. A
 * locked screen answers only a tap.
 */
internal suspend fun PointerInputScope.detectPictureGestures(actions: PictureActions) {
	val slop = viewConfiguration.touchSlop
	val holdAfter = viewConfiguration.longPressTimeoutMillis
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		val width = size.width.toFloat()
		val height = size.height.toFloat()
		if (width <= 0f || height <= 0f) return@awaitEachGesture
		if (actions.locked()) {
			val up = waitForUpOrCancellation() ?: return@awaitEachGesture
			if ((up.position - down.position).getDistance() <= slop) actions.lockedTap()
			return@awaitEachGesture
		}
		val id = down.id
		val holdAt = down.uptimeMillis + holdAfter
		var mode = Mode.Undecided
		var kind = SwipeKind.Brightness
		var last = down.position
		var zoom = 1f
		var pinchSent = false
		var pulled = 0f
		var tracker: VelocityTracker? = null
		try {
			while (true) {
				val event = if (mode == Mode.Undecided) {
					withTimeoutOrNull((holdAt - SystemClock.uptimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() }
				} else {
					awaitPointerEvent()
				}
				if (event == null) {
					mode = if (actions.canHold()) Mode.Hold.also { actions.hold(true) } else Mode.Idle
					continue
				}
				val pressed = event.changes.count { it.pressed }
				val primary = event.changes.firstOrNull { it.id == id }
				if (pressed == 0) {
					if (mode == Mode.Undecided && primary != null && primary.changedToUp()) actions.tap(down.position.x, width)
					break
				}
				if (mode == Mode.Undecided && pressed >= 2) mode = Mode.Pinch
				if (mode == Mode.Undecided && primary != null) {
					val moved = primary.position - down.position
					if (moved.getDistance() > slop) {
						if (abs(moved.y) > abs(moved.x)) {
							mode = Mode.Swipe
							kind = swipeKind(down.position.x, width)
							if (kind == SwipeKind.Minimise) {
								// The picture moves with the finger, and so do its coordinates: the pull is summed from the
								// changes. This move is added with the rest, so the slop is not lost.
								pulled = moved.y - primary.positionChange().y
								tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, Offset.Zero) }
								actions.minimiseBegan()
							} else {
								actions.swipeBegan(kind)
							}
						} else {
							mode = Mode.Scrub
							actions.scrubBegan()
						}
					}
				}
				when (mode) {
					Mode.Pinch -> {
						zoom *= event.calculateZoom()
						if (!pinchSent && pinchAspect(zoom) != null) {
							pinchSent = true
							actions.pinched(zoom)
						}
						event.changes.forEach { it.consume() }
					}
					Mode.Swipe -> if (primary != null && primary.pressed) {
						if (kind == SwipeKind.Minimise) {
							pulled += primary.positionChange().y
							tracker?.addPosition(primary.uptimeMillis, Offset(0f, pulled))
							actions.minimised(pulled)
						} else {
							actions.swiped(kind, primary.position.y - down.position.y, height)
						}
						primary.consume()
					} else {
						break
					}
					Mode.Scrub -> if (primary != null && primary.pressed) {
						// The first move counts from where the finger came down, so the slop is not lost.
						actions.scrubbed(primary.position.x - last.x, width)
						last = primary.position
						primary.consume()
					} else {
						break
					}
					Mode.Hold -> if (primary == null || !primary.pressed) break else primary.consume()
					Mode.Idle, Mode.Undecided -> Unit
				}
			}
		} finally {
			when (mode) {
				Mode.Hold -> actions.hold(false)
				Mode.Swipe -> if (kind == SwipeKind.Minimise) actions.minimiseEnded(tracker?.calculateVelocity()?.y ?: 0f)
				Mode.Scrub -> actions.scrubEnded()
				else -> Unit
			}
		}
	}
}

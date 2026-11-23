package com.nphil.marquee.ui.player

import android.content.pm.ActivityInfo
import kotlin.math.abs

/**
 * How the player moves between the full screen and the mini bar. The expansion says where it is: 1 is the full screen,
 * 0 is the bar docked above the tab bar. A finger on the picture or on the bar moves it, and the rules here say where
 * it comes to rest, so the gesture is plain arithmetic that needs no screen to test.
 */
internal enum class DockRest { Full, Bar }

/** The way a drag has gone once it has moved past the touch slop. */
internal enum class DockAxis { Vertical, Horizontal }

/** Where the player is in the app. */
internal enum class DockState { Closed, Full, Bar }

/** What happens to the player. A title ending, the bar's close button and a swipe that sends the bar away are all [Close]. */
internal enum class DockEvent {
	/** A title or channel is opened. It takes the full screen in place of any that was playing. */
	Open,

	/** Swiped down, or the minimise button. */
	Minimise,

	/** The bar tapped or swiped up. */
	Expand,

	/** Playback stops and the player goes. */
	Close,
}

/** Faster than this (dp per second) a flick decides the way by itself, wherever the finger lifted. */
private const val FLING_DP_PER_S = 400f

/** A drag that has not covered this share of the way between the two rests goes back to the one it left. */
private const val DRAG_SHARE = 0.3f

/** The share of its width the bar is dragged sideways before it is let go. */
private const val DISMISS_SHARE = 0.4f

/** A screen whose short side is narrower than this (dp) is a phone: the bar sits above the tab bar, and the player may turn it upright. */
private const val PHONE_BELOW_DP = 600

/**
 * The way a drag has gone, or null while it has not moved past [slop]. Up or down wins only when it is the larger
 * move, as the picture's own gestures decide it.
 */
internal fun dockAxis(dx: Float, dy: Float, slop: Float): DockAxis? = when {
	maxOf(abs(dx), abs(dy)) <= slop -> null
	abs(dy) > abs(dx) -> DockAxis.Vertical
	else -> DockAxis.Horizontal
}

/**
 * The expansion after a finger has moved [dy] pixels down (up is negative) from where the drag began, at [start].
 * [travel] is the whole way between the bar and the full screen, in pixels.
 */
internal fun dockExpansion(start: Float, dy: Float, travel: Float): Float =
	if (travel <= 0f) start else (start - dy / travel).coerceIn(0f, 1f)

/**
 * Where the player comes to rest when the finger lifts, the drag having begun at [from]. [velocity] is in dp per second,
 * positive toward the bottom of the screen. A quick flick settles it the way it points. A slow lift settles it by how far
 * it got: the same share of the way takes it off either rest.
 */
internal fun dockRest(from: DockRest, expansion: Float, velocity: Float): DockRest = when {
	velocity >= FLING_DP_PER_S -> DockRest.Bar
	velocity <= -FLING_DP_PER_S -> DockRest.Full
	from == DockRest.Full -> if (expansion < 1f - DRAG_SHARE) DockRest.Bar else DockRest.Full
	else -> if (expansion > DRAG_SHARE) DockRest.Full else DockRest.Bar
}

/**
 * Which way the bar is sent off when the finger lifts: -1 off the start of the screen, 1 off the end, 0 when it comes
 * back. [offset] and [width] are in the same unit; [velocity] is in dp per second, positive toward the end. A flick the
 * way the bar was moved lets it go, and so does dragging it [DISMISS_SHARE] of its width. A flick back keeps it.
 */
internal fun dockDismiss(offset: Float, velocity: Float, width: Float): Int {
	val side = when {
		offset > 0f -> 1
		offset < 0f -> -1
		else -> return 0
	}
	val along = velocity * side
	return when {
		along <= -FLING_DP_PER_S -> 0
		along >= FLING_DP_PER_S -> side
		width > 0f && abs(offset) >= width * DISMISS_SHARE -> side
		else -> 0
	}
}

/** The state after [event]. An event that means nothing in a state leaves it as it is. */
internal fun DockState.after(event: DockEvent): DockState = when (event) {
	DockEvent.Open -> DockState.Full
	DockEvent.Minimise -> if (this == DockState.Full) DockState.Bar else this
	DockEvent.Expand -> if (this == DockState.Bar) DockState.Full else this
	DockEvent.Close -> DockState.Closed
}

internal fun isPhone(smallestWidthDp: Int): Boolean = smallestWidthDp < PHONE_BELOW_DP

private val LandscapeLocks = setOf(
	ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
	ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
	ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
	ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
)

/**
 * The orientation to ask the activity for as the player is minimised, or null to leave it alone. A phone that the
 * player held on its side turns upright, since it was the player, not the person, that turned it. [requested] is the
 * activity's requested orientation.
 */
internal fun orientationOnMinimise(requested: Int, phone: Boolean): Int? =
	if (phone && requested in LandscapeLocks) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else null

/** The expansion at which the player's controls and captions start to show, and at which they are fully in. */
private const val OVERLAY_FROM = 0.55f
private const val OVERLAY_TO = 0.95f

/** The expansion by which the bar's own text and buttons have faded away. */
private const val BAR_FADES_BY = 0.4f

private fun smoothstep(from: Float, to: Float, x: Float): Float {
	val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
	return t * t * (3f - 2f * t)
}

/** How much of the player's controls and captions show at [expansion]: none near the bar, all at the full screen. */
internal fun overlayAlpha(expansion: Float): Float = smoothstep(OVERLAY_FROM, OVERLAY_TO, expansion)

/** How much of the bar's text and buttons show at [expansion]: all in the bar, none a short way up. */
internal fun barAlpha(expansion: Float): Float = 1f - smoothstep(0f, BAR_FADES_BY, expansion)

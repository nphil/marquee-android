package com.nphil.marquee.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.nphil.marquee.playback.ScrubPreviews
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import kotlin.math.abs

/** How far the playhead follows the finger, by how far the finger is from the bar. */
internal enum class ScrubPace(val rate: Double, val label: String?) {
	Full(1.0, null),
	Half(0.5, "Half-Speed Scrubbing"),
	Quarter(0.25, "Quarter-Speed Scrubbing"),
	Fine(0.1, "Fine Scrubbing"),
	;

	companion object {
		/** The pace for a finger [distance] dp above or below the bar's middle. */
		fun at(distance: Float): ScrubPace = when {
			distance < 44f -> Full
			distance < 90f -> Half
			distance < 150f -> Quarter
			else -> Fine
		}
	}
}

/** The bar and the picture both scrub through this: where the playhead is being taken, and how finely. */
@Stable
internal class ScrubState(
	private val began: () -> Unit,
	private val moved: (seconds: Double) -> Unit,
	private val ended: (seconds: Double) -> Unit,
) {
	var active by mutableStateOf(false)
		private set
	var seconds by mutableDoubleStateOf(0.0)
		private set
	var pace by mutableStateOf(ScrubPace.Full)

	fun begin(at: Double) {
		seconds = at
		pace = ScrubPace.Full
		active = true
		began()
		moved(at)
	}

	fun moveTo(at: Double) {
		if (!active) return
		seconds = at
		moved(at)
	}

	fun finish() {
		if (!active) return
		active = false
		pace = ScrubPace.Full
		ended(seconds)
	}
}

/** Cuts closer together than this share of the title are one cut. */
private const val SAME_CUT = 0.0005

/**
 * Where the bar is cut into segments, as fractions of the title in order: at each of [edges] (the starts of chapters and
 * the edges of the intro and credits). The start and end of the title are not cuts.
 */
internal fun seekCuts(durationMs: Long, edges: List<Long>): List<Float> {
	if (durationMs <= 0L) return emptyList()
	val cuts = ArrayList<Float>()
	var last = 0.0
	for (at in edges.map { it.toDouble() / durationMs }.filter { it > 0.0 && it < 1.0 }.sorted()) {
		if (at - last > SAME_CUT) cuts += at.toFloat()
		last = at
	}
	return cuts
}

/** The intro and credits marks as stretches of the bar, as fractions of the title. */
internal fun seekBands(durationMs: Long, marks: List<LongRange>): List<ClosedFloatingPointRange<Float>> {
	if (durationMs <= 0L) return emptyList()
	return marks.mapNotNull { mark ->
		val from = (mark.first.toFloat() / durationMs).coerceIn(0f, 1f)
		val to = (mark.last.toFloat() / durationMs).coerceIn(0f, 1f)
		if (to > from) from..to else null
	}
}

internal val BAR_HIT = 56.dp
private val TRACK = 6.dp
private val TRACK_ACTIVE = 8.dp
private val THUMB = 16.dp
private const val THUMB_ACTIVE = 1.4f
private val CUT_GAP = 2.dp
private const val BUFFERED_ALPHA = 0.38f

/**
 * The seek bar: the played part in the accent, what is buffered paler, the chapters and the intro and credits marks cut
 * into it. A touch jumps to where it lands and a drag moves the playhead from there; the further the finger is from
 * the bar, the finer the move.
 */
@Composable
internal fun SeekBar(
	state: ChromeState,
	scrub: ScrubState,
	accent: Color,
	onSkip: (Int) -> Unit,
	onBounds: (Rect) -> Unit,
	modifier: Modifier = Modifier,
) {
	val haptic = LocalHapticFeedback.current
	val duration = state.durationMs.coerceAtLeast(1L)
	val grow = animateFloatAsState(if (scrub.active) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "bar grow")
	val watched = state.positionMs
	Canvas(
		modifier
			.fillMaxWidth()
			.height(BAR_HIT)
			.onGloballyPositioned { onBounds(it.boundsInRoot()) }
			.pointerInput(state.durationMs) { detectSeekDrag(scrub, state.durationMs, haptic) }
			.semantics {
				contentDescription = "Playback position"
				stateDescription = "${clock(watched)} of ${clock(state.durationMs)}"
				progressBarRangeInfo = ProgressBarRangeInfo((watched.toFloat() / duration).coerceIn(0f, 1f), 0f..1f)
				setProgress { target ->
					onSkip(if (target >= watched.toFloat() / duration) state.skipSeconds else -state.skipSeconds)
					true
				}
			},
	) {
		val shown = if (scrub.active) (scrub.seconds * 1000.0).toLong() else watched
		val played = (shown.toFloat() / duration).coerceIn(0f, 1f)
		val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(played, 1f)
		val width = size.width
		val trackHeight = lerp(TRACK, TRACK_ACTIVE, grow.value).toPx()
		val radius = lerp(THUMB, THUMB * THUMB_ACTIVE, grow.value).toPx() / 2f
		val top = (size.height - trackHeight) / 2f
		val thumbX = (played * width).coerceIn(radius, maxOf(radius, width - radius))
		val gap = CUT_GAP.toPx()

		val mask = Path()
		var from = 0f
		for (cut in state.cuts) {
			val x = cut * width
			// A cut that near the end of the bar would leave a sliver.
			if (x <= trackHeight || x >= width - trackHeight) continue
			mask.addRect(Rect(from, top, x - gap / 2f, top + trackHeight))
			from = x + gap / 2f
		}
		mask.addRect(Rect(from, top, width, top + trackHeight))
		val capsule = Path().apply { addRoundRect(RoundRect(0f, top, width, top + trackHeight, CornerRadius(trackHeight / 2f))) }
		clipPath(capsule) {
			clipPath(mask) {
				drawRect(PlayerInk.trackOff, Offset(0f, top), Size(width, trackHeight))
				for (band in state.bands) {
					drawRect(PlayerInk.trackMark, Offset(band.start * width, top), Size((band.endInclusive - band.start) * width, trackHeight))
				}
				drawRect(accent.copy(alpha = BUFFERED_ALPHA), Offset(0f, top), Size(buffered * width, trackHeight))
				drawRect(accent, Offset(0f, top), Size(thumbX, trackHeight))
			}
		}
		val centre = Offset(thumbX, size.height / 2f)
		drawCircle(Color.Black.copy(alpha = 0.32f), radius + 1.5.dp.toPx(), centre)
		drawCircle(PlayerInk.ink, radius, centre)
	}
}

private suspend fun PointerInputScope.detectSeekDrag(scrub: ScrubState, durationMs: Long, haptic: HapticFeedback) {
	if (durationMs <= 0L) return
	val duration = durationMs / 1000.0
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		val width = size.width.toFloat()
		if (width <= 0f) return@awaitEachGesture
		down.consume()
		scrub.begin((down.position.x / width).coerceIn(0f, 1f) * duration)
		var last = down.position.x
		try {
			drag(down.id) { change ->
				val pace = ScrubPace.at(abs(change.position.y - size.height / 2f) / density)
				if (pace != scrub.pace) {
					scrub.pace = pace
					haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
				}
				scrub.moveTo(scrubbed(scrub.seconds, change.position.x - last, width, duration * pace.rate, duration))
				last = change.position.x
				change.consume()
			}
		} finally {
			scrub.finish()
		}
	}
}

internal val PREVIEW_WIDTH = 224.dp
private val PREVIEW_GAP = 12.dp
private val PREVIEW_INSET = 16.dp

/** What the bubble needs above the bar to show a picture as well as the time. */
private val PREVIEW_ROOM = 196.dp

/**
 * The bubble over the thumb while scrubbing: the picture at the playhead when the title has previews, and its time.
 * It sits [PREVIEW_GAP] above the thumb and stays [PREVIEW_INSET] inside the safe area. It shows the time alone when
 * there are no previews, none decoded yet, or not enough room above the bar.
 */
@Composable
internal fun SeekPreview(
	scrub: ScrubState,
	durationMs: Long,
	previews: ScrubPreviews?,
	bar: () -> Rect,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val direction = LocalLayoutDirection.current
	val insets = WindowInsets.safeDrawing
	val left = insets.getLeft(density, direction)
	val right = insets.getRight(density, direction)
	val top = insets.getTop(density)
	val origin = remember { mutableStateOf(Offset.Zero) }
	val haptic = LocalHapticFeedback.current
	val kept = remember { arrayOfNulls<ImageBitmap>(1) }

	// Reading the revision is what recomposes the bubble when a picture arrives.
	previews?.revision
	val found = previews?.frame(scrub.seconds)
	if (found != null) kept[0] = found
	val frame = kept[0]
	LaunchedEffect(found) {
		if (found != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
	}

	val room = bar().top - origin.value.y - top
	val withPicture = frame != null && room >= with(density) { PREVIEW_ROOM.toPx() }
	val gap = with(density) { PREVIEW_GAP.roundToPx() }
	val inset = with(density) { PREVIEW_INSET.roundToPx() }
	val thumbRadius = with(density) { THUMB.toPx() * THUMB_ACTIVE / 2f }

	Layout(
		content = { PreviewBubble(frame.takeIf { withPicture }, clock((scrub.seconds * 1000.0).toLong()), scrub.pace) },
		modifier = modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInRoot() },
	) { measurables, constraints ->
		val bubble = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
		layout(constraints.maxWidth, constraints.maxHeight) {
			val rect = bar()
			val fraction = (scrub.seconds * 1000.0 / durationMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat()
			val thumbX = rect.left + (fraction * rect.width).coerceIn(thumbRadius, maxOf(thumbRadius, rect.width - thumbRadius)) - origin.value.x
			val half = bubble.width / 2f
			val least = left + inset + half
			val most = maxOf(least, constraints.maxWidth - right - inset - half)
			val x = thumbX.coerceIn(least, most) - half
			val bottom = rect.center.y - thumbRadius - origin.value.y - gap
			val y = maxOf(top.toFloat(), bottom - bubble.height)
			bubble.place(IntOffset(x.toInt(), y.toInt()))
		}
	}
}

@Composable
private fun PreviewBubble(frame: ImageBitmap?, time: String, pace: ScrubPace) {
	Column(Modifier.width(PREVIEW_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		if (frame != null) {
			val shape = RoundedCornerShape(Radius.tile)
			Image(
				bitmap = frame,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).border(1.dp, PlayerInk.edge, shape),
			)
		}
		Column(
			Modifier.clip(CircleShape).background(PlayerInk.chip).padding(horizontal = Space.s3, vertical = Space.s1),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Text(
				time,
				style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
				color = PlayerInk.ink,
				fontWeight = FontWeight.SemiBold,
			)
			pace.label?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PlayerInk.ink2) }
		}
	}
}

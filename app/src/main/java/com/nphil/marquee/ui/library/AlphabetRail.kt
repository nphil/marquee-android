package com.nphil.marquee.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nphil.marquee.core.AlphabetEntry
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The tag of the rail, for tests that drive it. */
const val LibraryRailTag = "library.alphabetRail"

private val RowHeight = 16.dp
private val RailWidth = Size.target / 2 + Space.s2
private val CalloutSide = 80.dp

/** How many letters fit a rail [height] px tall at [row] px each: never fewer than two, the first and the last. */
internal fun railFitting(height: Float, row: Float): Int = max(floor(height / row).toInt(), 2)

/** Every letter when they fit; else an even sample of [fitting] of them, always with the first and the last. */
internal fun railLetters(entries: List<AlphabetEntry>, fitting: Int): List<AlphabetEntry> {
	val rows = max(fitting, 2)
	if (entries.size <= rows) return entries
	val step = (entries.size - 1).toDouble() / (rows - 1)
	return List(rows) { entries[(it * step).roundToInt()] }
}

/**
 * The index in all [entryCount] letters that a touch at [y] means. The shown letters are centred in the rail, and the
 * span they cover is divided among every letter, so a sampled rail still reaches all of them.
 */
internal fun railEntryIndex(y: Float, height: Float, shownCount: Int, rowHeight: Float, entryCount: Int): Int {
	val band = shownCount * rowHeight
	val top = (height - band) / 2
	val fraction = ((y - top) / max(band, 1f)).coerceIn(0f, 0.999f)
	return (fraction * entryCount).toInt()
}

/**
 * The A–Z rail on the grid's trailing edge: a touch or a drag over it jumps the grid to that letter, with a tick under the
 * finger and the letter large beside it. For a screen reader it is one adjustable control that steps letter by letter.
 */
@Composable
internal fun AlphabetRail(entries: List<AlphabetEntry>, onSelect: (AlphabetEntry) -> Unit, modifier: Modifier = Modifier) {
	val select by rememberUpdatedState(onSelect)
	val haptics = LocalHapticFeedback.current
	var current by remember { mutableStateOf<String?>(null) }
	var touchY by remember { mutableFloatStateOf(0f) }
	var stepped by remember { mutableIntStateOf(0) }
	val panel = lucent.sheet.compositeOver(lucent.canvas)
	val shape = RoundedCornerShape(Radius.control)
	BoxWithConstraints(modifier.width(RailWidth).fillMaxHeight()) {
		val density = LocalDensity.current
		val rowPx = with(density) { RowHeight.toPx() }
		val heightPx = with(density) { maxHeight.toPx() }
		val shown = remember(entries, heightPx) { railLetters(entries, railFitting(heightPx, rowPx)) }
		fun pick(index: Int) {
			val entry = entries[index.coerceIn(0, entries.lastIndex)]
			stepped = entries.indexOf(entry)
			select(entry)
		}
		Column(
			modifier = Modifier
				.align(Alignment.Center)
				.testTag(LibraryRailTag)
				.systemGestureExclusion()
				.pointerInput(entries, shown, heightPx) {
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						fun touch(y: Float) {
							touchY = y
							val index = railEntryIndex(y, heightPx, shown.size, rowPx, entries.size)
							val letter = entries[index].character
							if (letter == current) return
							current = letter
							haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							pick(index)
						}
						touch(down.position.y + (heightPx - shown.size * rowPx) / 2)
						down.consume()
						drag(down.id) { change ->
							touch(change.position.y + (heightPx - shown.size * rowPx) / 2)
							change.consume()
						}
						current = null
					}
				}
				.semantics {
					contentDescription = "Alphabet"
					stateDescription = entries[stepped.coerceIn(0, entries.lastIndex)].character
					progressBarRangeInfo = ProgressBarRangeInfo(stepped.toFloat(), 0f..entries.lastIndex.toFloat(), max(entries.size - 2, 0))
					setProgress { target ->
						pick(target.roundToInt())
						true
					}
					customActions = listOf(
						CustomAccessibilityAction("Next letter") {
							pick(stepped + 1)
							true
						},
						CustomAccessibilityAction("Previous letter") {
							pick(stepped - 1)
							true
						},
					)
				}
				.background(if (current != null) panel else panel.copy(alpha = 0f), shape),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			for (entry in shown) {
				Box(Modifier.size(RailWidth, RowHeight), contentAlignment = Alignment.Center) {
					Text(
						entry.character,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						color = if (entry.character == current) lucent.accentText else lucent.ink2,
						textAlign = TextAlign.Center,
					)
				}
			}
		}
		current?.let { letter ->
			val side = with(density) { CalloutSide.toPx() }
			val gap = with(density) { Space.s3.toPx() }
			Box(
				modifier = Modifier
					.offset {
						IntOffset(
							(-(side + gap)).roundToInt(),
							(touchY - side / 2).coerceIn(0f, max(heightPx - side, 0f)).roundToInt(),
						)
					}
					.size(CalloutSide)
					.background(panel, RoundedCornerShape(Radius.card))
					.border(1.dp, lucent.edge, RoundedCornerShape(Radius.card)),
				contentAlignment = Alignment.Center,
			) {
				Text(letter, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = lucent.accentText)
			}
		}
	}
}

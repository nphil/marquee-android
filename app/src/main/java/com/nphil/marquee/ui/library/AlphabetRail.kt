package com.nphil.marquee.ui.library

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nphil.marquee.core.AlphabetEntry
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The tag of the rail, for tests that drive it. */
const val LibraryRailTag = "library.alphabetRail"

private val RowHeight = 16.dp
private val RailWidth = Size.target / 2 + Space.s2

/** Room for the touched letter leaning 46 dp out and for two letters either side of it. */
private val LensWidth = Size.target * 2

/** Larger type would not fit the rows or the lens. */
private const val MAX_FONT_SCALE = 1.3f

/** The iPhone's interactive spring, 0.15 s response and 0.86 damping: the lens trails the finger by a beat. */
private val Follow = spring<Float>(dampingRatio = 0.86f, stiffness = 1755f)

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

/** Whether the system has animations switched off, which the rail answers by holding still instead of swelling. */
private fun animationsOff(resolver: ContentResolver): Boolean =
	Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The A–Z rail on the grid's trailing edge: a touch or a drag anywhere along it jumps the grid to that letter, with a
 * tick under the finger. The letters near the finger swell and lean out over the grid on a panel that follows it, and
 * the letter shows large beside the panel. For a screen reader it is one adjustable control that steps letter by letter.
 */
@Composable
internal fun AlphabetRail(entries: List<AlphabetEntry>, onSelect: (AlphabetEntry) -> Unit, modifier: Modifier = Modifier) {
	val select by rememberUpdatedState(onSelect)
	val haptics = LocalHapticFeedback.current
	val resolver = LocalContext.current.contentResolver
	val scope = rememberCoroutineScope()
	var current by remember { mutableStateOf<String?>(null) }
	var touching by remember { mutableStateOf(false) }
	var still by remember { mutableStateOf(false) }
	// Kept after the finger lifts, so the callout fades out still showing its letter.
	var callout by remember { mutableStateOf("") }
	var stepped by remember { mutableIntStateOf(0) }
	// The finger's height on the rail and how far the lens is open, 0 at rest and 1 under a finger. Only drawing reads
	// them, so a moving finger redraws the letters without recomposing the rail.
	val fingerY = remember { Animatable(0f) }
	val strength = remember { Animatable(0f) }
	val panel = lucent.sheet.compositeOver(lucent.canvas)
	val screen = LocalDensity.current
	val type = remember(screen) { Density(screen.density, min(screen.fontScale, MAX_FONT_SCALE)) }
	val rest = MaterialTheme.typography.labelSmall
	// Set at the lens's peak size and scaled down, since text scaled up is a stretched bitmap when drawn through a layer.
	val letterStyle = rest.copy(
		fontSize = rest.fontSize * RailLens.PEAK,
		lineHeight = rest.fontSize * RailLens.PEAK,
		fontWeight = FontWeight.SemiBold,
		textAlign = TextAlign.Center,
	)
	val calloutStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold)
	val glyph = with(type) { rest.fontSize.toDp() }.value
	val calloutSide = with(type) { maxOf(calloutStyle.lineHeight.toDp(), calloutStyle.fontSize.toDp()) } + Space.s3 * 2
	val lensHeight = RailLens.panelHeight(RowHeight.value, glyph, LensWidth.value).dp
	val lensShape = RoundedCornerShape(Radius.tile)
	val calloutShape = RoundedCornerShape(Radius.card)

	fun lift() {
		touching = false
		current = null
		scope.launch { if (still) strength.snapTo(0f) else strength.animateTo(0f, Follow) }
	}

	CompositionLocalProvider(LocalDensity provides type) {
		BoxWithConstraints(modifier.width(RailWidth).fillMaxHeight()) {
			val rowPx = with(type) { RowHeight.toPx() }
			val heightPx = with(type) { maxHeight.toPx() }
			val shown = remember(entries, heightPx) { railLetters(entries, railFitting(heightPx, rowPx)) }
			val topPx = (heightPx - shown.size * rowPx) / 2
			val lensPx = with(type) { lensHeight.toPx() }
			val calloutPx = with(type) { calloutSide.toPx() }
			fun pick(index: Int) {
				val entry = entries[index.coerceIn(0, entries.lastIndex)]
				stepped = entries.indexOf(entry)
				select(entry)
			}
			Box(
				modifier = Modifier
					.fillMaxSize()
					.testTag(LibraryRailTag)
					.systemGestureExclusion()
					.pointerInput(entries, shown, heightPx) {
						awaitEachGesture {
							val down = awaitFirstDown(requireUnconsumed = false)
							still = animationsOff(resolver)
							fun touch(y: Float, first: Boolean) {
								touching = true
								scope.launch {
									when {
										first -> {
											fingerY.snapTo(y)
											if (still) strength.snapTo(1f) else strength.animateTo(1f, Follow)
										}
										still -> fingerY.snapTo(y)
										else -> fingerY.animateTo(y, Follow)
									}
								}
								val index = railEntryIndex(y, heightPx, shown.size, rowPx, entries.size)
								val letter = entries[index].character
								if (letter == current) return
								current = letter
								callout = letter
								haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								pick(index)
							}
							try {
								touch(down.position.y, first = true)
								down.consume()
								drag(down.id) { change ->
									touch(change.position.y, first = false)
									change.consume()
								}
							} finally {
								lift()
							}
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
					},
			) {
				Box(
					Modifier
						.align(Alignment.TopStart)
						.offset(x = RailWidth - LensWidth)
						.wrapContentSize(Alignment.TopStart, unbounded = true)
						.size(LensWidth, lensHeight)
						.graphicsLayer {
							translationY = (fingerY.value - lensPx / 2f).coerceIn(0f, max(heightPx - lensPx, 0f))
							alpha = strength.value.coerceIn(0f, 1f)
						}
						.background(panel, lensShape)
						.border(1.dp, lucent.edge, lensShape)
						.clearAndSetSemantics {},
				)
				Column(
					Modifier.align(Alignment.Center).clearAndSetSemantics {},
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					shown.forEachIndexed { index, entry ->
						Box(
							Modifier
								.size(RailWidth, RowHeight)
								.graphicsLayer {
									val open = strength.value.coerceIn(0f, 1f)
									val offset = index - ((fingerY.value - topPx) / rowPx - 0.5f)
									val distance = abs(offset)
									scaleX = (1f + (RailLens.scale(distance, still) - 1f) * open) / RailLens.PEAK
									scaleY = scaleX
									if (!still) {
										translationX = -RailLens.lean(distance) * open * density
										translationY = RailLens.shift(offset, RowHeight.value, glyph) * open * density
									}
									alpha = 1f - (1f - RailLens.opacity(distance, still)) * open
									// A layer for the fade would clip the letter to its row, which it overflows.
									compositingStrategy = CompositingStrategy.ModulateAlpha
								},
							contentAlignment = Alignment.Center,
						) {
							Text(
								entry.character,
								modifier = Modifier.wrapContentSize(unbounded = true),
								color = when {
									entry.character == current -> lucent.accentText
									touching -> lucent.ink
									else -> lucent.ink2
								},
								style = letterStyle,
								maxLines = 1,
								softWrap = false,
							)
						}
					}
				}
				if (callout.isNotEmpty()) {
					Box(
						Modifier
							.align(Alignment.TopStart)
							.offset(x = RailWidth - LensWidth - Space.s3 - calloutSide)
							.wrapContentSize(Alignment.TopStart, unbounded = true)
							.size(calloutSide)
							.graphicsLayer {
								translationY = (fingerY.value - calloutPx / 2f).coerceIn(0f, max(heightPx - calloutPx, 0f))
								alpha = strength.value.coerceIn(0f, 1f)
							}
							.background(panel, calloutShape)
							.border(1.dp, lucent.edge, calloutShape)
							.padding(Space.s3)
							.clearAndSetSemantics {},
						contentAlignment = Alignment.Center,
					) {
						Text(callout, style = calloutStyle, color = lucent.accentText)
					}
				}
			}
		}
	}
}

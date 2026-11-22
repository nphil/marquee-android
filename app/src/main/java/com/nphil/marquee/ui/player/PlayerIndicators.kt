package com.nphil.marquee.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.ui.theme.Space
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A level the viewer is changing by swiping or a key: the brightness or the volume. */
@Stable
internal class Meter {
	var level by mutableFloatStateOf(0f)
		private set

	/** Counts the changes, so the same level shown twice still restarts the meter's dwell. */
	var stamp by mutableIntStateOf(0)
		private set

	fun show(level: Float) {
		this.level = level.coerceIn(0f, 1f)
		stamp++
	}
}

private val METER_WIDTH = 52.dp
private val METER_HEIGHT = 208.dp
private const val METER_DWELL_MS = 900L

/**
 * The meter beside the picture's edge while a level changes. Panel care: a 52 by 208 dp dark capsule with a light glyph
 * and an accent bar, still in one place, up for 0.9 s after the last change and then faded out.
 */
@Composable
internal fun MeterView(meter: Meter, icon: (Float) -> Int, accent: Color, modifier: Modifier = Modifier) {
	var visible by remember { mutableStateOf(false) }
	LaunchedEffect(meter.stamp) {
		if (meter.stamp == 0) return@LaunchedEffect
		visible = true
		delay(METER_DWELL_MS)
		visible = false
	}
	AnimatedVisibility(
		visible,
		modifier,
		enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
		exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()),
	) {
		Column(
			Modifier
				.width(METER_WIDTH)
				.height(METER_HEIGHT)
				.clip(RoundedCornerShape(METER_WIDTH / 2))
				.background(PlayerInk.chip)
				.padding(vertical = Space.s3),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Icon(painterResource(icon(meter.level)), contentDescription = null, tint = PlayerInk.ink, modifier = Modifier.size(24.dp))
			Spacer(Modifier.height(Space.s2))
			Box(
				Modifier.weight(1f).width(6.dp).clip(CircleShape).background(PlayerInk.trackOff),
				contentAlignment = Alignment.BottomCenter,
			) {
				Box(Modifier.fillMaxWidth().fillMaxHeight(meter.level).background(accent))
			}
			Spacer(Modifier.height(Space.s2))
			Text(
				"${(meter.level * 100).roundToInt()}",
				style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
				color = PlayerInk.ink,
			)
		}
	}
}

/** What a run of double-taps showed last, kept so the ripple does not change as it fades. */
private class RippleMemory {
	var total = 0
	var side = SkipSide.Forward
}

private const val RIPPLE_POP = 1.16f

/**
 * The double-tap skip's feedback: the third of the picture that was tapped goes dark with a fast-forward mark and the
 * running total, which pops each time it grows. [total] is the model's `skipping`, null once the skip has landed.
 * Panel care: it is up while the taps run and for about a second after (the model's own settle and feedback times),
 * then fades.
 */
@Composable
internal fun SkipRipple(total: Int?, modifier: Modifier = Modifier) {
	val memory = remember { RippleMemory() }
	if (total != null) {
		memory.side = skipSide(memory.total, total, memory.side)
		memory.total = total
	}
	val pop = remember { Animatable(1f) }
	val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
	LaunchedEffect(total) {
		if (total == null) return@LaunchedEffect
		pop.snapTo(RIPPLE_POP)
		pop.animateTo(1f, spring)
	}
	AnimatedVisibility(
		total != null,
		modifier,
		enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
		exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()),
	) {
		val side = memory.side
		Box(
			Modifier
				.fillMaxSize()
				.drawBehind {
					val span = zoneSpan(if (side == SkipSide.Back) Zone.Start else Zone.End, size.width)
					val round = CornerRadius(minOf(span.endInclusive - span.start, size.height / 2f))
					val shape = RoundRect(
						Rect(span.start, 0f, span.endInclusive, size.height),
						topLeft = if (side == SkipSide.Back) CornerRadius.Zero else round,
						topRight = if (side == SkipSide.Back) round else CornerRadius.Zero,
						bottomRight = if (side == SkipSide.Back) round else CornerRadius.Zero,
						bottomLeft = if (side == SkipSide.Back) CornerRadius.Zero else round,
					)
					drawPath(Path().apply { addRoundRect(shape) }, PlayerInk.chip)
				},
		) {
			// Thirds by weight, the way Core splits the picture: the mark sits in the middle of the tapped one.
			Row(Modifier.fillMaxSize()) {
				for (third in listOf(SkipSide.Back, null, SkipSide.Forward)) {
					Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
						if (third != null && third == side) {
							Column(
								Modifier.graphicsLayer {
									scaleX = pop.value
									scaleY = pop.value
								},
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(Space.s1),
							) {
								Icon(
									painterResource(R.drawable.ic_fast_forward),
									contentDescription = null,
									tint = PlayerInk.ink,
									modifier = Modifier
										.size(32.dp)
										.graphicsLayer { scaleX = if (side == SkipSide.Back) -1f else 1f },
								)
								Text(
									skipLabel(memory.total),
									style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
									color = PlayerInk.ink,
									fontWeight = FontWeight.SemiBold,
								)
							}
						}
					}
				}
			}
		}
	}
}

private val RIPPLE_LABEL = 96.dp

/** A short message in a capsule, with a glyph when it has one: the picture's new aspect, the controls' lock. */
@Composable
internal fun NotePill(text: String, @DrawableRes icon: Int?, modifier: Modifier = Modifier) {
	Row(
		modifier.clip(CircleShape).background(PlayerInk.chip).padding(horizontal = Space.s4, vertical = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = PlayerInk.ink, modifier = Modifier.size(20.dp))
		Text(text, style = MaterialTheme.typography.labelLarge, color = PlayerInk.ink, fontWeight = FontWeight.SemiBold)
	}
}

/**
 * [NotePill] for a moment after [stamp] changes. Panel care: a capsule at the top of the picture, still, up for [dwell]
 * milliseconds and then faded out.
 */
@Composable
internal fun Note(stamp: Int, text: String, @DrawableRes icon: Int?, dwell: Long, modifier: Modifier = Modifier) {
	var visible by remember { mutableStateOf(false) }
	LaunchedEffect(stamp) {
		if (stamp == 0) return@LaunchedEffect
		visible = true
		delay(dwell)
		visible = false
	}
	AnimatedVisibility(
		visible,
		modifier,
		enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
		exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()),
	) {
		NotePill(text, icon)
	}
}

/** The double-speed hold's mark, up only while the finger is held. */
@Composable
internal fun HoldPill(visible: Boolean, modifier: Modifier = Modifier) {
	AnimatedVisibility(
		visible,
		modifier,
		enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
		exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
	) {
		NotePill("2\u00D7 Speed", R.drawable.ic_fast_forward)
	}
}

package com.nphil.marquee.ui.tvremote

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.ui.theme.Size as TokenSize
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** A thumb rests on the stick this far out before it counts as fully deflected, as a share of the disc's radius. */
private const val StickReach = 0.62f
private const val KnobRadius = 0.26f

/** Width of the mode switch: three segments, never wider than the pad. */
private val SwitchWidth = 168.dp

/**
 * The remote's thumb area. [mode] picks how it behaves; [touchpad] says the TV can take pointer input at all, and
 * without it the saved touchpad choice is kept but the D-pad shows. The mode switch sits under the pad when there is room.
 */
@Composable
internal fun RemotePad(
	mode: PadMode, onMode: (PadMode) -> Unit, ready: Boolean, touchpad: Boolean, input: (RemoteRequest) -> Unit,
	modifier: Modifier = Modifier,
) {
	val shown = if (mode == PadMode.Touchpad && !touchpad) PadMode.Dpad else mode
	BoxWithConstraints(modifier) {
		val withSwitch = maxHeight >= 120.dp
		val stage = (maxHeight - if (withSwitch) TokenSize.target + Space.s3 else 0.dp).coerceAtLeast(0.dp)
		Box(Modifier.fillMaxWidth().height(stage), contentAlignment = Alignment.Center) {
			Crossfade(shown, Modifier.fillMaxSize(), animationSpec = tween(150), label = "padMode") { current ->
				if (current == PadMode.Touchpad) {
					TouchSurface(ready, input, Modifier.fillMaxSize())
				} else {
					BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						Box(Modifier.size(min(maxWidth, maxHeight))) {
							if (current == PadMode.Joystick) Joystick(ready, input, Modifier.fillMaxSize()) else DirectionPad(ready, input, Modifier.fillMaxSize())
						}
					}
				}
			}
		}
		if (withSwitch) {
			PadModeSwitch(shown, onMode, touchpad, Modifier.align(Alignment.BottomCenter).widthIn(max = SwitchWidth).fillMaxWidth())
		}
	}
}

@Composable
private fun PadModeSwitch(mode: PadMode, onMode: (PadMode) -> Unit, touchpad: Boolean, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(percent = 50)
	Row(modifier.height(TokenSize.target).clip(shape).background(lucent.glassRaised).border(1.dp, lucent.edge, shape).selectableGroup()) {
		ModeSegment(PadMode.Dpad, R.drawable.ic_dpad, "Directional pad", mode, true, onMode, Modifier.weight(1f))
		ModeSegment(PadMode.Joystick, R.drawable.ic_joystick, "Joystick", mode, true, onMode, Modifier.weight(1f))
		ModeSegment(PadMode.Touchpad, R.drawable.ic_touchpad, "Touchpad", mode, touchpad, onMode, Modifier.weight(1f))
	}
}

@Composable
private fun ModeSegment(
	entry: PadMode, icon: Int, label: String, mode: PadMode, enabled: Boolean, onMode: (PadMode) -> Unit, modifier: Modifier,
) {
	val selected = mode == entry
	val interaction = remember { MutableInteractionSource() }
	val pressed by interaction.collectIsPressedAsState()
	val scale by animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "segmentPress")
	val fill by animateColorAsState(if (selected) lucent.accent else lucent.accent.copy(alpha = 0f), tween(150), label = "segmentFill")
	val ink = when {
		!enabled -> lucent.ink3
		selected -> lucent.accentInk
		else -> lucent.ink2
	}
	Box(
		modifier.fillMaxHeight().padding(3.dp).graphicsLayer { scaleX = scale; scaleY = scale }
			.clip(RoundedCornerShape(percent = 50)).background(fill)
			.selectable(selected, interaction, null, enabled, Role.RadioButton) { onMode(entry) }
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center,
	) {
		Icon(painterResource(icon), null, tint = ink)
	}
}

private val ArrowAngles = mapOf(DpadKey.Right to 0f, DpadKey.Down to 90f, DpadKey.Left to 180f, DpadKey.Up to 270f)

private fun DrawScope.chevron(at: Offset, degrees: Float, color: Color, reach: Float) {
	val path = Path().apply {
		moveTo(-reach * 0.5f, -reach)
		lineTo(reach * 0.5f, 0f)
		lineTo(-reach * 0.5f, reach)
	}
	translate(at.x, at.y) {
		rotate(degrees, Offset.Zero) {
			drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
		}
	}
}

private fun arrowActions(emit: (RemoteRequest) -> Unit) = listOf("Up", "Down", "Left", "Right").map { name ->
	CustomAccessibilityAction(name) {
		emit(RemoteRequest(key = name.lowercase()))
		true
	}
}

/**
 * The classic D-pad: four wedges and OK in the middle. A tap sends the key on release; holding an arrow hands the
 * TV a held key so its own repeat takes over, and holding OK is a long press.
 */
@Composable
private fun DirectionPad(ready: Boolean, input: (RemoteRequest) -> Unit, modifier: Modifier = Modifier) {
	val emit by rememberUpdatedState(input)
	val haptics = LocalHapticFeedback.current
	var pressed by remember { mutableStateOf<DpadKey?>(null) }
	val plate = lucent.glassRaised
	val edge = lucent.edge
	val wedge = lucent.focus
	val ink = lucent.ink2
	val focusInk = lucent.focusInk
	val ok = lucent.accent
	val actions = remember { arrowActions { emit(it) } }
	Box(
		modifier.graphicsLayer { alpha = if (ready) 1f else 0.5f }
			.testTag("tv-dpad")
			.semantics {
				contentDescription = "Directional pad"
				onClick("Select") {
					emit(RemoteRequest(key = "ok"))
					true
				}
				customActions = actions
			}
			.pointerInput(ready) {
				if (!ready) return@pointerInput
				awaitEachGesture {
					val down = awaitFirstDown()
					val centre = Offset(size.width / 2f, size.height / 2f)
					val key = dpadKeyAt(down.position - centre, size.width / 2f) ?: return@awaitEachGesture
					down.consume()
					pressed = key
					haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
					var holding = false
					try {
						var released: PointerInputChange? = null
						var settled = false
						withTimeoutOrNull(if (key == DpadKey.Ok) 500L else 300L) {
							released = waitForUpOrCancellation()
							settled = true
						}
						val up = released
						if (settled) {
							if (up != null) {
								up.consume()
								emit(RemoteRequest(key = key.key))
							}
						} else {
							haptics.performHapticFeedback(HapticFeedbackType.LongPress)
							if (key == DpadKey.Ok) {
								emit(RemoteRequest(key = key.key, press = "hold"))
							} else {
								holding = true
								emit(RemoteRequest(key = key.key, press = "down"))
							}
							waitForUpOrCancellation()?.consume()
						}
					} finally {
						if (holding) emit(RemoteRequest(key = key.key, press = "up"))
						pressed = null
					}
				}
			}
			.drawBehind {
				val r = size.minDimension / 2f
				drawCircle(plate, r, center)
				val lit = pressed
				if (lit != null && lit != DpadKey.Ok) {
					drawArc(wedge, ArrowAngles.getValue(lit) - 45f, 90f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
				}
				drawCircle(edge, r, center, style = Stroke(1.dp.toPx()))
				for (angle in listOf(45f, 135f, 225f, 315f)) {
					val rad = Math.toRadians(angle.toDouble())
					val direction = Offset(cos(rad).toFloat(), sin(rad).toFloat())
					drawLine(edge, center + direction * (r * OkRadius), center + direction * r, 1.dp.toPx())
				}
				for ((key, degrees) in ArrowAngles) {
					val rad = Math.toRadians(degrees.toDouble())
					val at = center + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * (r * 0.72f)
					chevron(at, degrees, if (lit == key) focusInk else ink, r * 0.09f)
				}
				drawCircle(if (lit == DpadKey.Ok) wedge else ok, r * OkRadius, center)
			},
		contentAlignment = Alignment.Center,
	) {
		Icon(painterResource(R.drawable.ic_check), null, Modifier.fillMaxSize(0.2f), tint = if (pressed == DpadKey.Ok) lucent.focusInk else lucent.accentInk)
	}
}

/**
 * The same four keys as a stick. How far the thumb leans sets how fast the arrow steps repeat, so a small push crawls
 * through a list and a full one runs. Steps are single taps the TV never has to time out: each lands at the pace its
 * focus navigation accepts, which holding a key could not promise.
 */
@Composable
private fun Joystick(ready: Boolean, input: (RemoteRequest) -> Unit, modifier: Modifier = Modifier) {
	val emit by rememberUpdatedState(input)
	val haptics = LocalHapticFeedback.current
	val scope = rememberCoroutineScope()
	val repeater = remember { JoystickRepeater() }
	var touching by remember { mutableStateOf(false) }
	var thumb by remember { mutableStateOf(Offset.Zero) }
	var lit by remember { mutableStateOf<DpadKey?>(null) }
	val rest = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
	val plate = lucent.glassRaised
	val edge = lucent.edge
	val ink = lucent.ink2
	val knob = lucent.accent
	val actions = remember { arrowActions { emit(it) } }

	// Leans on the stick for the current thumb and sends the step that is due; true when one was sent.
	fun lean(reach: Float): Boolean {
		val deflection = stickDeflection(thumb, reach)
		val step = repeater.update(SystemClock.uptimeMillis(), deflection.x, deflection.y)
		if (step != null) {
			haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
			emit(RemoteRequest(key = step.key))
		}
		if (lit != repeater.held) lit = repeater.held
		return step != null
	}

	var reachPx by remember { mutableFloatStateOf(1f) }
	LaunchedEffect(touching) {
		if (!touching) return@LaunchedEffect
		while (true) withFrameNanos { lean(reachPx) }
	}

	Box(
		modifier.graphicsLayer { alpha = if (ready) 1f else 0.5f }
			.testTag("tv-joystick")
			.semantics {
				contentDescription = "Joystick"
				onClick("Select") {
					emit(RemoteRequest(key = "ok"))
					true
				}
				customActions = actions
			}
			.pointerInput(ready) {
				if (!ready) return@pointerInput
				awaitEachGesture {
					val down = awaitFirstDown()
					down.consume()
					val centre = Offset(size.width / 2f, size.height / 2f)
					val reach = size.width / 2f * StickReach
					reachPx = reach
					thumb = down.position - centre
					val atRest = stickDeflection(thumb, reach).getDistance() < JoystickRepeater.DeadIn
					var stepped = false
					var lastUp = down.uptimeMillis
					touching = true
					if (lean(reach)) stepped = true
					try {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							change.consume()
							lastUp = change.uptimeMillis
							if (!change.pressed) break
							thumb = change.position - centre
							if (lean(reach)) stepped = true
						}
					} finally {
						touching = false
						repeater.release()
						lit = null
						val from = stickDeflection(thumb, reach) * reach
						scope.launch(start = CoroutineStart.UNDISPATCHED) {
							rest.snapTo(from)
							rest.animateTo(Offset.Zero, spring(dampingRatio = 1f, stiffness = 584f))
						}
						// A touch that never left the dead zone is a press of OK.
						if (!stepped && atRest && lastUp - down.uptimeMillis <= 250L) {
							haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
							emit(RemoteRequest(key = "ok"))
						}
					}
				}
			}
			.drawBehind {
				val r = size.minDimension / 2f
				val reach = r * StickReach
				drawCircle(plate, r, center)
				drawCircle(edge, r, center, style = Stroke(1.dp.toPx()))
				drawCircle(edge, reach * JoystickRepeater.DeadIn, center, style = Stroke(1.dp.toPx()))
				for ((key, degrees) in ArrowAngles) {
					val rad = Math.toRadians(degrees.toDouble())
					val at = center + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * (r * 0.84f)
					chevron(at, degrees, if (lit == key) knob else ink, r * 0.07f)
				}
				val at = center + if (touching) stickDeflection(thumb, reach) * reach else rest.value
				drawCircle(knob, r * KnobRadius, at)
			},
	)
}

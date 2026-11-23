package com.nphil.marquee.bigscreen

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/** The phone as the remote (DESIGN §8.9): the whole screen takes the gestures, dimmed while it shows. */
private const val DIMMED_BRIGHTNESS = 0.15f

/** A still thumb becomes a hold after this long; a thumb held still this long after a swipe hands scrolling to tilt. */
private const val HOLD_MS = 450L
private const val STILL_MS = 350L

/** Touches starting this close to the left edge go back once they travel [BACK_TRAVEL] right; the top edge leaves. */
private const val EDGE_WIDTH = 20.0
private const val TOP_HEIGHT = 24.0
private const val BACK_TRAVEL = 80.0

/** Tilt's top rates: focus steps a second when scrolling, playhead seconds a second when scrubbing. */
private const val SCROLL_RATE = 12.0
private const val SCRUB_RATE = 30.0

/** Points of a two-finger swipe for one volume step. */
private const val VOLUME_STRIDE = 36.0

@Composable
internal fun GlassesRemoteOverlay(container: AppContainer) {
	val center = container.glasses
	val navigator = center.navigator
	if (!center.showsRemote || navigator == null) return
	val context = LocalContext.current
	val haptics = remember { RemoteHaptics(context) }
	DisposableEffect(navigator) {
		navigator.onCue = haptics::play
		onDispose { navigator.onCue = null }
	}
	DimWindow()
	BackHandler { navigator.handle(GlassesInput.Back) }
	GlassesTheme(container) {
		RemoteFace(center, navigator, haptics)
	}
}

/** The window is dimmed while the remote shows; the phone's own level comes back after. */
@Composable
private fun DimWindow() {
	val context = LocalContext.current
	DisposableEffect(context) {
		val window = (context as? Activity)?.window
		val before = window?.attributes?.screenBrightness
		window?.attributes = window?.attributes?.also { it.screenBrightness = DIMMED_BRIGHTNESS }
		onDispose {
			window?.attributes = window?.attributes?.also { it.screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
		}
	}
}

/**
 * What the phone shows while it is the remote: the focused thing for a glance, and the gestures, faint. Nothing on it
 * is a target; the whole screen is one surface, with a strip above it for the way out.
 */
@Composable
private fun RemoteFace(center: GlassesCenter, navigator: GlassesNavigator, haptics: RemoteHaptics) {
	val context = LocalContext.current
	val tilt = remember { RemoteTilt(context) }
	DisposableEffect(tilt) { onDispose { tilt.stop() } }
	val title = navigator.focusTitle
	val detail = navigator.focusDetail
	Box(
		Modifier
			.fillMaxSize()
			.background(lucent.canvas)
			.systemGestureExclusion()
			.pointerInput(navigator, center) { remoteGestures(center, navigator, haptics, tilt) }
			.semantics {
				contentDescription = title
				if (detail != null) stateDescription = detail
				customActions = listOf(
					CustomAccessibilityAction("Back") { navigator.handle(GlassesInput.Back); true },
					CustomAccessibilityAction("Options") {
						if (navigator.inPlayer) navigator.openPlayerOptions() else navigator.handle(GlassesInput.HoldBegan)
						true
					},
					CustomAccessibilityAction("Use the Phone App") { center.leaveRemote(); true },
					CustomAccessibilityAction("Volume Up") { haptics.play(volumeStep(center, up = true)); true },
					CustomAccessibilityAction("Volume Down") { haptics.play(volumeStep(center, up = false)); true },
					CustomAccessibilityAction("Choose") { navigator.handle(GlassesInput.Select); true },
				)
			}
			.testTag("glasses-remote"),
	) {
		Column(
			Modifier.fillMaxSize().safeDrawingPadding().padding(Space.inset),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			Box(Modifier.weight(1f))
			Icon(painterResource(center.symbol), null, tint = lucent.ink3, modifier = Modifier.padding(Space.s2))
			Text(title, style = MaterialTheme.typography.headlineSmall, color = lucent.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
			if (detail != null) {
				Text(detail, style = MaterialTheme.typography.bodyLarge, color = lucent.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
			}
			Box(Modifier.weight(1f))
			Text(
				"Swipe to move \u00B7 Tap to choose\nSwipe in from the left edge to go back\nSwipe down from the top edge to leave\n" +
					"Hold for options, or in a video to scrub\nTwo fingers up or down for volume",
				style = MaterialTheme.typography.bodySmall, color = lucent.ink3, textAlign = TextAlign.Center,
			)
		}
		ExitStrip(center, Modifier.align(Alignment.TopCenter))
	}
}

/**
 * The way out, above the gesture surface and clear of the top edge, which is a gesture of its own: labelled buttons
 * that need a deliberate tap, so a swipe never lands on them.
 */
@Composable
private fun ExitStrip(center: GlassesCenter, modifier: Modifier) {
	Row(
		modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = Space.inset).padding(top = Space.s7),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		FilledTonalButton(onClick = center::leaveRemote, modifier = Modifier.testTag("glasses-exit")) {
			Icon(painterResource(R.drawable.ic_smartphone), null, modifier = Modifier.padding(end = Space.s2))
			Text("Phone App")
		}
		IconButton(onClick = center::openSettings, modifier = Modifier.testTag("glasses-settings")) {
			Icon(painterResource(R.drawable.ic_settings), "Settings", tint = lucent.ink2)
		}
	}
}

/** Home's button while a display is connected and the phone app shows: it brings the remote back. */
@Composable
internal fun GlassesRemoteButton(center: GlassesCenter) {
	IconButton(onClick = center::showRemote, modifier = Modifier.testTag("glasses-button")) {
		Icon(painterResource(center.symbol), center.remoteTitle, tint = lucent.accentText)
	}
}

/** The first connection of a display asks what it is for. */
@Composable
internal fun GlassesAskDialog(center: GlassesCenter) {
	if (!center.asksToUse) return
	AlertDialog(
		onDismissRequest = { center.answer(null) },
		title = { Text(center.question) },
		text = {
			Text("Marquee can fill it with its own screen and turn the phone into a remote, or leave it mirroring the phone.")
		},
		confirmButton = {
			Column(horizontalAlignment = Alignment.End) {
				TextButton(onClick = { center.answer(DisplayKind.Glasses) }) { Text("Use as XR Glasses") }
				TextButton(onClick = { center.answer(DisplayKind.Tv) }) { Text("Use as TV") }
				TextButton(onClick = { center.answer(null) }) { Text("Mirror") }
			}
		},
		containerColor = lucent.sheet,
	)
}

private fun volumeStep(center: GlassesCenter, up: Boolean): HapticCue =
	if (center.stepVolume(up)) HapticCue.VolumeLimit else HapticCue.VolumeStep

// MARK: Touches

private enum class Phase { Touching, Edge, Top, Swiping, Tilting, Held, Done }

private suspend fun PointerInputScope.remoteGestures(center: GlassesCenter, navigator: GlassesNavigator, haptics: RemoteHaptics, tilt: RemoteTilt) {
	val width = (size.width / density).toDouble()
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		val touch = RemoteTouch(center, navigator, haptics, tilt, density, width)
		touch.down(down)
		try {
			while (true) {
				val wait = touch.waitMs
				val event = if (wait == null) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
				if (event == null) touch.timeout() else if (touch.event(event)) break
			}
		} finally {
			touch.finish()
		}
	}
}

/**
 * The remote's one touch surface: swipes step the focus and a flick coasts on; a tap chooses; a swipe in from the left
 * edge goes back; a hold opens the options or, in a video, scrubs while the thumb slides. With tilt on, holding the
 * thumb still after a swipe lets the phone's tilt keep scrolling, and tilt fine-tunes a scrub. Motion runs only while
 * the thumb is down. Distances are in dp, as the iPhone's are in points.
 */
private class RemoteTouch(
	private val center: GlassesCenter,
	private val navigator: GlassesNavigator,
	private val haptics: RemoteHaptics,
	private val tilt: RemoteTilt,
	private val density: Float,
	private val width: Double,
) {
	private var phase = Phase.Touching
	private var id: PointerId? = null
	private var start = Offset.Zero
	private var anchor = Offset.Zero
	private val tracker = VelocityTracker()
	private var axis = SwipeSteps()
	private var tiltBlocked: FocusMove? = null
	private var tiltMove: FocusMove? = null
	private var volumeStart = 0f
	private var volumeSent = 0
	private var volumeOn = false

	/** How long to wait for the next event before the thumb counts as still; null waits for it. */
	val waitMs: Long?
		get() = when (phase) {
			Phase.Touching, Phase.Edge, Phase.Top -> HOLD_MS
			Phase.Swiping -> if (navigator.tiltEnabled) STILL_MS else null
			else -> null
		}

	private fun dp(px: Float) = (px / density).toDouble()

	fun down(change: PointerInputChange) {
		id = change.id
		start = change.position
		anchor = start
		tracker.addPointerInputChange(change)
		navigator.handle(GlassesInput.PanBegan)
		phase = when {
			dp(start.x) <= EDGE_WIDTH -> Phase.Edge
			dp(start.y) <= TOP_HEIGHT -> Phase.Top
			else -> Phase.Touching
		}
	}

	/** Whether the gesture is over. */
	fun event(event: PointerEvent): Boolean {
		val pressed = event.changes.filter { it.pressed }
		if (!volumeOn && pressed.size > 1 && phase != Phase.Held) beginVolume(pressed)
		if (volumeOn) {
			if (pressed.isNotEmpty()) swipeVolume(pressed)
			event.changes.forEach { it.consume() }
			return pressed.isEmpty()
		}
		val change = event.changes.firstOrNull { it.id == id } ?: return false
		tracker.addPointerInputChange(change)
		if (!change.pressed) {
			lifted(change)
			change.consume()
			return true
		}
		moved(change)
		change.consume()
		return false
	}

	/** Nothing moved for the delay: a hold, or after a swipe, tilt takes over. */
	fun timeout() {
		if (phase == Phase.Swiping) tiltScroll() else hold()
	}

	fun finish() {
		tilt.stop()
		if (phase == Phase.Held) navigator.handle(GlassesInput.HoldEnded)
		phase = Phase.Done
	}

	// MARK: Moves

	private fun moved(change: PointerInputChange) {
		val dx = dp(change.position.x - anchor.x)
		val dy = dp(change.position.y - anchor.y)
		when (phase) {
			Phase.Held -> navigator.handle(GlassesInput.Slide(dx, width))
			Phase.Edge -> when {
				dx >= BACK_TRAVEL && dx > abs(dy) -> {
					phase = Phase.Done
					navigator.handle(GlassesInput.Back)
				}
				abs(dy) >= SwipeSteps.LOCK_DISTANCE && abs(dy) > dx -> swipe(dx, dy)
			}
			Phase.Top -> when {
				dy >= BACK_TRAVEL && dy > abs(dx) -> {
					phase = Phase.Done
					center.leaveRemote()
				}
				abs(dx) >= SwipeSteps.LOCK_DISTANCE && abs(dx) > dy -> swipe(dx, dy)
			}
			Phase.Tilting -> if (hypot(dx, dy) >= SwipeSteps.LOCK_DISTANCE) {
				// The thumb moves again: a new pan from where tilt began.
				tilt.stop()
				axis = SwipeSteps()
				navigator.handle(GlassesInput.PanBegan)
				swipe(dx, dy)
			}
			Phase.Touching, Phase.Swiping -> swipe(dx, dy)
			Phase.Done -> Unit
		}
	}

	/** The first points find the axis, for holds and tilt; the navigator does the scrolling, one pan a frame. */
	private fun swipe(dx: Double, dy: Double) {
		if (axis.axis == null) {
			axis.moved(dx, dy)
			if (axis.axis == null) return
		}
		phase = Phase.Swiping
		navigator.handle(GlassesInput.Pan(dx, dy))
	}

	private fun lifted(change: PointerInputChange) {
		val velocity = tracker.calculateVelocity()
		val vx = dp(velocity.x)
		val vy = dp(velocity.y)
		val dx = dp(change.position.x - start.x)
		val dy = dp(change.position.y - start.y)
		val still = hypot(dx, dy) < SwipeSteps.LOCK_DISTANCE
		val ended = phase
		// A hold ends in [finish], which tells the navigator.
		if (ended != Phase.Held) phase = Phase.Done
		when (ended) {
			Phase.Touching -> navigator.handle(GlassesInput.Select)
			Phase.Edge -> if (dx >= SwipeSteps.LOCK_DISTANCE && vx >= SwipeSteps.FLICK_SPEED) navigator.handle(GlassesInput.Back)
			else if (still) navigator.handle(GlassesInput.Select)
			Phase.Top -> if (dy >= SwipeSteps.LOCK_DISTANCE && vy >= SwipeSteps.FLICK_SPEED) center.leaveRemote()
			else if (still) navigator.handle(GlassesInput.Select)
			Phase.Swiping -> navigator.handle(GlassesInput.PanEnded(vx, vy))
			Phase.Held, Phase.Tilting, Phase.Done -> Unit
		}
	}

	// MARK: Hold and tilt

	private fun hold() {
		phase = Phase.Held
		navigator.handle(GlassesInput.HoldBegan)
		if (!navigator.inPlayer || !navigator.isScrubbing || !navigator.tiltEnabled) return
		val drive = TiltDrive(SCRUB_RATE)
		tilt.start { roll, _, seconds ->
			val amount = drive.amount(roll, seconds)
			if (amount != 0.0) navigator.handle(GlassesInput.Nudge(amount))
		}
	}

	private fun tiltScroll() {
		val along = axis.axis ?: return
		phase = Phase.Tilting
		// Tilt takes over from the thumb: the page settles on the nearest item.
		navigator.handle(GlassesInput.PanEnded(0.0, 0.0))
		tiltBlocked = null
		tiltMove = null
		val drive = TiltDrive(SCROLL_RATE)
		val horizontal = along == SwipeSteps.Axis.Horizontal
		tilt.start { roll, pitch, seconds ->
			val count = drive.steps(if (horizontal) roll else pitch, seconds)
			if (count == 0) return@start
			val move = if (horizontal) (if (count > 0) FocusMove.Right else FocusMove.Left) else (if (count > 0) FocusMove.Down else FocusMove.Up)
			if (move == tiltBlocked) return@start
			tiltBlocked = null
			tiltMove = move
			repeat(abs(count)) {
				if (navigator.step(move) == HapticCue.Edge) {
					tiltBlocked = move
					return@start
				}
			}
		}
	}

	// MARK: Volume

	private fun beginVolume(pressed: List<PointerInputChange>) {
		tilt.stop()
		if (phase == Phase.Swiping || phase == Phase.Tilting) navigator.handle(GlassesInput.PanEnded(0.0, 0.0))
		phase = Phase.Done
		volumeOn = true
		volumeSent = 0
		volumeStart = pressed.map { it.position.y }.average().toFloat()
	}

	/** Two fingers up or down: one volume step for each [VOLUME_STRIDE] dp. */
	private fun swipeVolume(pressed: List<PointerInputChange>) {
		val y = pressed.map { it.position.y }.average().toFloat()
		val total = (-dp(y - volumeStart) / VOLUME_STRIDE).toInt()
		val delta = total - volumeSent
		volumeSent = total
		repeat(abs(delta)) { haptics.play(volumeStep(center, up = delta > 0)) }
	}
}

// MARK: Haptics and tilt

/** Plays the remote's touch vocabulary on the phone's vibrator, each cue as its own pattern. */
internal class RemoteHaptics(context: Context) {
	private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		(context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
	} else {
		@Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
	}

	fun play(cue: HapticCue) {
		val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
		val timings = ArrayList<Long>()
		val strengths = ArrayList<Int>()
		var at = 0L
		for (event in cue.events) {
			val begin = (event.time * 1000).toLong()
			if (begin > at) {
				timings += begin - at
				strengths += 0
				at = begin
			}
			val length = if (event.duration > 0) (event.duration * 1000).toLong() else TAP_MS
			timings += length
			strengths += (event.intensity * 255).toInt().coerceIn(1, 255)
			at += length
		}
		motor.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), strengths.toIntArray(), -1))
	}

	private companion object {
		/** A tap is a short pulse: long enough for the motor to start, short enough to stay crisp. */
		const val TAP_MS = 12L
	}
}

/**
 * The phone's tilt from where it was when [start] was called, as radians of roll (right side down is positive) and
 * pitch (top away is positive), with the seconds since the last reading. Runs only between [start] and [stop].
 */
internal class RemoteTilt(context: Context) : SensorEventListener {
	private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
	private var onReading: ((roll: Double, pitch: Double, seconds: Double) -> Unit)? = null
	private var reference: FloatArray? = null
	private var lastNanos = 0L
	private val matrix = FloatArray(9)
	private val angles = FloatArray(3)

	fun start(onReading: (roll: Double, pitch: Double, seconds: Double) -> Unit) {
		stop()
		val motion = sensor ?: return
		this.onReading = onReading
		sensors.registerListener(this, motion, SensorManager.SENSOR_DELAY_GAME)
	}

	fun stop() {
		if (onReading == null) return
		sensors.unregisterListener(this)
		onReading = null
		reference = null
		lastNanos = 0L
	}

	override fun onSensorChanged(event: SensorEvent) {
		val report = onReading ?: return
		SensorManager.getRotationMatrixFromVector(matrix, event.values)
		SensorManager.getOrientation(matrix, angles)
		val base = reference ?: angles.copyOf().also {
			reference = it
			lastNanos = event.timestamp
		}
		val seconds = (event.timestamp - lastNanos) / 1e9
		lastNanos = event.timestamp
		report((angles[2] - base[2]).toDouble(), (angles[1] - base[1]).toDouble(), seconds)
	}

	override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}

package com.nphil.marquee.ui.tvremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A touchpad for the TV's pointer: one finger moves it, a tap clicks, a tap and a drag drags, two fingers scroll and a
 * quick two-finger tap is Back. The gesture rules live in [TouchpadSession]; this only reads the fingers and runs its
 * frame loop. The corner keys re-centre the pointer, lock a drag, and switch to the phone's tilt as the mouse.
 */
@Composable
internal fun TouchSurface(ready: Boolean, input: (RemoteRequest) -> Unit, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val sensors = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
	val gyro = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
	val gravity = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) }
	val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
	val canPoint = gyro != null && gravity != null
	val emit by rememberUpdatedState(input)
	val session = remember { TouchpadSession { emit(it) } }
	var lock by remember { mutableStateOf(false) }
	var air by remember { mutableStateOf(false) }
	var touching by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf(false) }
	var finger by remember { mutableStateOf<Offset?>(null) }

	DisposableEffect(session) { onDispose { session.stop() } }
	// The glide, the pacing and the release after the last touch all run per frame, and only while there is something to run.
	LaunchedEffect(busy) {
		if (!busy) return@LaunchedEffect
		while (true) {
			withFrameNanos { session.tick(SystemClock.uptimeMillis()) }
			if (!session.busy) {
				busy = false
				return@LaunchedEffect
			}
		}
	}

	val shape = RoundedCornerShape(Radius.card)
	val fill by animateColorAsState(if (touching) lucent.glassRaised else lucent.glass, tween(150), label = "touchpadFill")
	val ring = lucent.accent.copy(alpha = 0.22f)
	Box(
		modifier.clip(shape).background(fill).border(1.dp, if (touching) lucent.edgeRaised else lucent.edge, shape)
			.testTag("tv-touchpad")
			.semantics {
				contentDescription = if (air) "Air mouse" else "TV touchpad"
				onClick("Click TV pointer") {
					emit(RemoteRequest(pointer = "click"))
					true
				}
			}
			.pointerInput(ready) {
				if (!ready) return@pointerInput
				awaitEachGesture {
					val down = awaitFirstDown()
					down.consume()
					var last = down.position
					touching = true
					finger = last
					session.touch(1, last.x / density, last.y / density, down.uptimeMillis)
					busy = true
					try {
						do {
							val event = awaitPointerEvent()
							val pressed = event.changes.filter { it.pressed }
							if (pressed.isNotEmpty()) last = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } / pressed.size.toFloat()
							session.touch(pressed.size, last.x / density, last.y / density, event.changes.maxOf { it.uptimeMillis })
							finger = if (pressed.isEmpty()) null else last
							event.changes.forEach { it.consume() }
						} while (pressed.isNotEmpty())
					} finally {
						// A cancelled gesture still lifts the fingers, so a held button and the pointer are let go.
						if (session.touching) session.touch(0, last.x / density, last.y / density, SystemClock.uptimeMillis())
						touching = false
						finger = null
						busy = session.busy
					}
				}
			}
			.drawBehind { finger?.let { drawCircle(ring, 28.dp.toPx(), it) } },
		contentAlignment = Alignment.Center,
	) {
		Icon(painterResource(R.drawable.ic_touchpad), null, Modifier.size(40.dp).graphicsLayer { alpha = 0.5f }, tint = lucent.ink3)
		Box(Modifier.align(Alignment.TopStart).padding(Space.s2)) {
			RemoteGlyph("Centre TV pointer", R.drawable.ic_contrast, onClick = { emit(RemoteRequest(pointer = "centre")) })
		}
		Box(Modifier.align(Alignment.BottomEnd).padding(Space.s2)) {
			RemoteGlyph("Drag lock", R.drawable.ic_drag_handle, emphasized = lock, onClick = {
				lock = !lock
				session.lock = lock
			})
		}
		if (canPoint) {
			Box(Modifier.align(Alignment.TopEnd).padding(Space.s2)) {
				RemoteGlyph(if (air) "Touchpad" else "Air mouse", R.drawable.ic_smartphone, emphasized = air, onClick = {
					air = !air
					session.air = air
				})
			}
		}
	}

	if (air && touching && sensors != null && gyro != null && gravity != null) {
		DisposableEffect(sensors, rotation) {
			val listener = object : SensorEventListener {
				var time = 0L
				var downX = 0.0
				var downY = 0.0
				var downZ = -1.0

				override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
				override fun onSensorChanged(event: SensorEvent) {
					val values = event.values
					if (event.sensor.type == Sensor.TYPE_GRAVITY) {
						val length = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
						if (length > 0.1f) {
							downX = -values[0].toDouble() / length
							downY = -values[1].toDouble() / length
							downZ = -values[2].toDouble() / length
						}
						return
					}
					val elapsed = if (time == 0L) 0.0 else (event.timestamp - time) / 1_000_000_000.0
					time = event.timestamp
					if (elapsed <= 0 || elapsed > 0.08) return
					val yaw = values[0] * downX + values[1] * downY + values[2] * downZ
					// Project the screen's horizontal axis onto the plane normal to gravity.
					val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
					val sign = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_180) -1 else 1
					val projection = sign * if (sideways) downY else downX
					val across = sign * values[if (sideways) 1 else 0]
					val length = sqrt((1 - projection * projection).coerceAtLeast(0.0))
					val pitch = if (length > 0.1) (across - projection * yaw) / length else across.toDouble()
					val gain = 2750 * elapsed * (0.45 + 0.35 * hypot(yaw, pitch)).coerceIn(0.45, 1.6)
					emit(RemoteRequest(pointer = "move", x = gain * yaw.deadZone(), y = -gain * pitch.deadZone()))
				}
			}
			sensors.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
			sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
			onDispose { sensors.unregisterListener(listener) }
		}
	}
}

private fun Double.deadZone(): Double = this * this * this / (this * this + 0.0001)

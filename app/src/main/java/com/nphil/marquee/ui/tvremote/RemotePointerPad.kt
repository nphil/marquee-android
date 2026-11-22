package com.nphil.marquee.ui.tvremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.hypot
import kotlin.math.sqrt

@Composable
internal fun RemotePointerPad(input: (RemoteRequest) -> Unit) {
	val context = LocalContext.current
	val sensors = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
	val gyro = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
	val gravity = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) }
	val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
	val canPoint = gyro != null && gravity != null
	val emit by rememberUpdatedState(input)
	var air by remember { mutableStateOf(false) }
	var engaged by remember { mutableStateOf(false) }
	var drag by remember { mutableStateOf(false) }
	val shape = RoundedCornerShape(Radius.card)
	Column(Modifier.widthIn(max = 184.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		Box(Modifier.size(width = 176.dp, height = 144.dp).clip(shape)
			.background(if (engaged) lucent.glassRaised else lucent.canvas)
			.border(1.dp, if (engaged) lucent.edgeRaised else lucent.edge, shape)
			.semantics {
				contentDescription = if (air) "Air mouse" else "TV touchpad"
				onClick(label = "Click TV pointer") { emit(RemoteRequest(pointer = "click")); true }
			}
			.pointerInput(air, drag) {
				awaitEachGesture {
					val down = awaitFirstDown()
					down.consume()
					engaged = true
					emit(RemoteRequest(pointer = "begin"))
					if (drag) emit(RemoteRequest(pointer = "down"))
					var fingers = 1
					var total = Offset.Zero
					var moved = false
					var up = down.uptimeMillis
					try {
						do {
							val event = awaitPointerEvent()
							val pressed = event.changes.count { it.pressed }
							fingers = maxOf(fingers, pressed)
							var delta = Offset.Zero
							var moving = 0
							for (change in event.changes) {
								up = maxOf(up, change.uptimeMillis)
								if (change.pressed && change.previousPressed) { delta += change.positionChange(); moving++ }
								change.consume()
							}
							if (moving > 0) {
								delta /= moving.toFloat()
								total += delta
								moved = moved || total.getDistance() >= viewConfiguration.touchSlop
								if (moved && !air) {
									val scroll = fingers > 1
									val gain = (if (scroll) -1.5 else 4.0) / density
									emit(RemoteRequest(pointer = if (scroll) "scroll" else "move", x = delta.x * gain, y = delta.y * gain))
								}
							}
						} while (pressed > 0)
						if (!drag && !moved && up - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
							if (fingers == 1) emit(RemoteRequest(pointer = "click"))
							else if (fingers == 2) emit(RemoteRequest(key = "back"))
						}
					} finally {
						if (drag) emit(RemoteRequest(pointer = "up"))
						emit(RemoteRequest(pointer = "end"))
						engaged = false
					}
				}
			}, contentAlignment = Alignment.Center) {
			Text(if (air) "Hold here and tilt" else "Touchpad", Modifier.padding(Space.s2),
				color = lucent.ink2, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
		}
		FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s1, Alignment.CenterHorizontally)) {
			RemoteGlyph("Centre TV pointer", R.drawable.ic_contrast, onClick = { emit(RemoteRequest(pointer = "centre")) })
			RemoteButton("Drag", onClick = { drag = !drag }, emphasized = drag)
			if (canPoint) RemoteButton(if (air) "Touchpad" else "Air mouse", onClick = { air = !air })
		}
	}
	if (air && engaged && sensors != null && gyro != null && gravity != null) {
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

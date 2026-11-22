package com.nphil.marquee.ui.tvremote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.tvremote.SavedTv
import com.nphil.marquee.tvremote.TvStatus
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

@Composable
internal fun RemoteFace(
	tv: SavedTv?, status: TvStatus?, input: (RemoteRequest) -> Unit, onPower: () -> Unit,
	onTvs: () -> Unit, onPair: () -> Unit, onKeyboard: () -> Unit, onMore: () -> Unit, onReconnect: () -> Unit,
) {
	val ready = status?.ready == true
	var usePointer by rememberSaveable { mutableStateOf(false) }
	val showingPointer = ready && status?.pointer == true && usePointer
	Surface(shape = RoundedCornerShape(Radius.card), color = lucent.glass, border = BorderStroke(1.dp, lucent.edge)) {
		Row(Modifier.fillMaxWidth().padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s2),
			verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
				verticalArrangement = Arrangement.spacedBy(Space.s1)) {
				Text(tv?.name ?: "LumaTV", style = MaterialTheme.typography.titleMedium)
				val label = when (status?.status) {
					"connected" -> if (status.standby) "Standby" else "Connected"
					"offline" -> "Offline · reconnecting"
					"incompatible" -> "TV needs a compatible version"
					"unpaired" -> "Pair again"
					else -> if (tv != null) "Connecting…" else "No TV paired"
				}
				Text(label, color = if (ready) lucent.accentText else lucent.ink2, style = MaterialTheme.typography.bodyMedium)
			}
			RemoteGlyph("Choose TV", R.drawable.ic_tv, onClick = onTvs)
			RemoteGlyph(if (status?.status == "connected" && !status.standby) "TV power" else "Wake TV",
				R.drawable.ic_power, enabled = tv != null && status?.status != "unpaired", emphasized = true, onClick = onPower)
		}
	}
	if (status?.status == "unpaired") RemoteButton("Pair again", onClick = onPair, emphasized = true)
	if (status?.status == "offline") RemoteButton("Reconnect", onClick = onReconnect)
	BoxWithConstraints(Modifier.fillMaxWidth()) {
		if (maxWidth >= 436.dp) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s4, Alignment.CenterHorizontally)) {
				Pad(ready, showingPointer, input)
				SecondaryControls(ready, status?.volume, status?.muted, input)
			}
		} else {
			Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				Pad(ready, showingPointer, input)
				SecondaryControls(ready, status?.volume, status?.muted, input)
			}
		}
	}
	FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
		verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		RemoteButton(if (showingPointer) "D-pad" else "Pointer", onClick = { usePointer = !usePointer },
			enabled = ready && status?.pointer == true)
		RemoteGlyph("TV keyboard", R.drawable.ic_keyboard, enabled = ready && status?.keyboard == true, onClick = onKeyboard)
		RemoteGlyph("Input", R.drawable.ic_alt_route, enabled = ready, onClick = { input(RemoteRequest(key = "input")) })
		RemoteGlyph("Guide", R.drawable.ic_calendar_month, enabled = ready, onClick = { input(RemoteRequest(key = "guide")) })
		RemoteGlyph("More controls", R.drawable.ic_more_horiz, onClick = onMore)
	}
}

@Composable
private fun Pad(ready: Boolean, pointer: Boolean, input: (RemoteRequest) -> Unit) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		if (pointer) RemotePointerPad(input) else DirectionPad(ready, input)
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Key("back", "TV Back", R.drawable.ic_arrow_back, ready, input, hold = true)
			Key("home", "TV Home", R.drawable.ic_home, ready, input)
			Key("menu", "TV Settings", R.drawable.ic_settings, ready, input, hold = true)
		}
	}
}

@Composable
private fun DirectionPad(ready: Boolean, input: (RemoteRequest) -> Unit) {
	Surface(shape = RoundedCornerShape(Radius.card), color = lucent.canvas, border = BorderStroke(1.dp, lucent.edge),
		modifier = Modifier.testTag("tv-dpad")) {
		Column(Modifier.padding(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1),
			horizontalAlignment = Alignment.CenterHorizontally) {
			Key("up", "Up", R.drawable.ic_expand_more, ready, input, turn = 180f, repeat = true)
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
				Key("left", "Left", R.drawable.ic_chevron_right, ready, input, turn = 180f, repeat = true)
				Key("ok", "Select", R.drawable.ic_check, ready, input, hold = true, emphasized = true)
				Key("right", "Right", R.drawable.ic_chevron_right, ready, input, repeat = true)
			}
			Key("down", "Down", R.drawable.ic_expand_more, ready, input, repeat = true)
		}
	}
}

@Composable
private fun SecondaryControls(ready: Boolean, volume: Double?, muted: Boolean?, input: (RemoteRequest) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.s2), horizontalAlignment = Alignment.CenterHorizontally) {
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.Top) {
			Rocker("Volume", "volup", "voldown", ready, input)
			Rocker("Channel", "chup", "chdown", ready, input)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Key("rew", "Rewind", R.drawable.ic_fast_forward, ready, input, turn = 180f, repeat = true)
			Key("playpause", "Play or pause TV", R.drawable.ic_play_arrow, ready, input)
			Key("ff", "Fast forward", R.drawable.ic_fast_forward, ready, input, repeat = true)
			Key("mute", if (muted == true) "Unmute TV" else "Mute TV", R.drawable.ic_volume_off, ready, input,
				emphasized = muted == true)
		}
		if (volume != null && ready) {
			Text(if (muted == true) "Muted · ${volume.displayLevel()}" else "Volume ${volume.displayLevel()}",
				style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		}
	}
}

@Composable
private fun Rocker(label: String, up: String, down: String, ready: Boolean, input: (RemoteRequest) -> Unit) {
	Surface(shape = RoundedCornerShape(Radius.row), color = lucent.glass, border = BorderStroke(1.dp, lucent.edge)) {
		Column(Modifier.padding(Space.s2), horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			RemoteSectionLabel(label)
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
				Key(down, "$label down", R.drawable.ic_expand_more, ready, input, repeat = true)
				Key(up, "$label up", R.drawable.ic_expand_more, ready, input, turn = 180f, repeat = true)
			}
		}
	}
}

@Composable
private fun Key(
	key: String, label: String, @DrawableRes icon: Int, enabled: Boolean, input: (RemoteRequest) -> Unit,
	turn: Float = 0f, repeat: Boolean = false, hold: Boolean = false, emphasized: Boolean = false,
) {
	val emit by rememberUpdatedState(input)
	val interaction = remember { MutableInteractionSource() }
	val pressed by interaction.collectIsPressedAsState()
	var held by remember { mutableStateOf(false) }
	fun release() {
		if (held) {
			held = false
			emit(RemoteRequest(key = key, press = "up"))
		}
	}
	if (repeat) {
		LaunchedEffect(interaction) {
			interaction.interactions.collect { if (it is PressInteraction.Release || it is PressInteraction.Cancel) release() }
		}
		DisposableEffect(Unit) { onDispose { release() } }
	}
	RemoteGlyph(label, icon, enabled, emphasized, turn, interaction, onLongClick = when {
		repeat -> ({
			// Accessibility can invoke a long click without a physical release event.
			if (pressed) { held = true; emit(RemoteRequest(key = key, press = "down")) }
			else emit(RemoteRequest(key = key, press = "hold"))
		})
		hold -> ({ emit(RemoteRequest(key = key, press = "hold")) })
		else -> null
	}, onClick = { emit(RemoteRequest(key = key)) })
}

@Composable
internal fun RemoteGlyph(
	label: String, @DrawableRes icon: Int, enabled: Boolean = true, emphasized: Boolean = false,
	turn: Float = 0f, interaction: MutableInteractionSource = remember { MutableInteractionSource() },
	onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
	val pressed by interaction.collectIsPressedAsState()
	val focused by interaction.collectIsFocusedAsState()
	val fill = when {
		pressed || focused -> lucent.glassRaised
		emphasized && enabled -> lucent.accent
		else -> lucent.sheet
	}
	val ink = when {
		!enabled -> lucent.ink3
		emphasized && !pressed && !focused -> lucent.accentInk
		else -> lucent.ink
	}
	Box(Modifier.size(Size.target).clip(CircleShape).background(fill)
		.border(1.dp, if (focused) lucent.accentText else lucent.edge, CircleShape)
		.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
			onLongClick = onLongClick, onClick = onClick), contentAlignment = Alignment.Center) {
		Icon(painterResource(icon), label, tint = ink, modifier = Modifier.rotate(turn))
	}
}

@Composable
internal fun RemoteButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, emphasized: Boolean = false) {
	val interactions = remember { MutableInteractionSource() }
	val focused by interactions.collectIsFocusedAsState()
	OutlinedButton(onClick, modifier.heightIn(min = Size.target), enabled = enabled,
		shape = RoundedCornerShape(Radius.control), border = BorderStroke(1.dp, if (focused) lucent.accentText else lucent.edgeRaised),
		colors = ButtonDefaults.outlinedButtonColors(containerColor = if (emphasized) lucent.accent else lucent.glass,
			contentColor = if (emphasized) lucent.accentInk else lucent.ink, disabledContentColor = lucent.ink3),
		interactionSource = interactions) {
		Text(label, style = MaterialTheme.typography.labelLarge)
	}
}

@Composable
internal fun RemoteSectionLabel(label: String) {
	Text(label, color = lucent.ink2, style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun RemoteMore(status: TvStatus?, input: (RemoteRequest) -> Unit, list: (String) -> Unit, act: (RemoteRequest) -> Unit) {
	val ready = status?.ready == true
	val available = status?.actions.orEmpty()
	RemoteSectionLabel("Playback")
	RemoteButtons {
		for ((key, label) in listOf("play" to "Play", "pause" to "Pause", "stop" to "Stop")) {
			RemoteButton(label, onClick = { input(RemoteRequest(key = key)) }, enabled = ready)
		}
	}
	RemoteSectionLabel("TV keys")
	RemoteButtons {
		for ((key, label) in listOf("info" to "Info", "more" to "Options", "exit" to "Exit", "list" to "TV list",
			"red" to "Red", "green" to "Green", "yellow" to "Yellow", "blue" to "Blue", "netflix" to "Netflix", "amazon" to "Prime Video")) {
			RemoteButton(label, onClick = { input(RemoteRequest(key = key)) }, enabled = ready)
		}
		RemoteButton("All settings", onClick = {
			if ("settings" in available) act(RemoteRequest(action = "settings")) else input(RemoteRequest(key = "menu", press = "hold"))
		}, enabled = ready)
	}
	if (available.isNotEmpty()) {
		RemoteSectionLabel("LumaTV")
		RemoteButtons {
			for ((action, label) in listOf("quick" to "Quick Settings", "notifications" to "Notifications", "input" to "Inputs",
				"pictureoff" to "Picture off", "cameras" to "Camera panel", "camera-close" to "Close camera")) {
				if (action in available) RemoteButton(label, onClick = { act(RemoteRequest(action = action)) }, enabled = ready)
			}
			if ("launch" in available) RemoteButton("All apps", onClick = { list("apps") }, enabled = ready)
			if ("tune" in available) RemoteButton("Channels", onClick = { list("channels") }, enabled = ready)
			if ("camera" in available) RemoteButton("Cameras", onClick = { list("cameras") }, enabled = ready)
		}
		val max = status?.volumeMax?.takeIf { it > 0 && it <= 250 }
		val level = status?.volume
		if ("volume" in available && max != null && level != null && ready) {
			var drag by remember { mutableStateOf<Float?>(null) }
			val shown = drag ?: level.toFloat().coerceIn(0f, max.toFloat())
			RemoteSectionLabel("Receiver volume · ${shown.toDouble().displayLevel()}")
			Slider(value = shown, onValueChange = { drag = it }, valueRange = 0f..max.toFloat(), onValueChangeFinished = {
				drag?.let { act(RemoteRequest(action = "volume", level = (it * 2).toInt() / 2.0)) }
				drag = null
			})
		}
	}
}

@Composable
private fun RemoteButtons(content: @Composable () -> Unit) {
	FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) { content() }
}

@Composable
internal fun RemoteKeyboard(status: TvStatus, input: (RemoteRequest) -> Unit) {
	var text by remember { mutableStateOf("") }
	fun send() { if (text.isNotEmpty()) { input(RemoteRequest(text = text)); text = "" } }
	val type = when (status.keyboardPurpose) {
		2, 3 -> KeyboardType.Number
		4 -> KeyboardType.Phone
		5 -> KeyboardType.Uri
		6 -> KeyboardType.Email
		else -> if (status.privateText) KeyboardType.Password else KeyboardType.Text
	}
	OutlinedTextField(text, { text = it }, label = { Text("Text to send") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
		visualTransformation = if (status.privateText) PasswordVisualTransformation() else VisualTransformation.None)
	RemoteButtons {
		RemoteButton("Send text", onClick = { send() }, enabled = text.isNotEmpty(), emphasized = true)
		RemoteGlyph("TV Backspace", R.drawable.ic_backspace, onClick = { input(RemoteRequest(edit = "backspace")) })
		RemoteButton("Enter", onClick = { send(); input(RemoteRequest(edit = "enter")) })
		RemoteButton("Tab", onClick = { input(RemoteRequest(edit = "tab")) })
		RemoteGlyph("Move caret left", R.drawable.ic_chevron_right, turn = 180f, onClick = { input(RemoteRequest(edit = "left")) })
		RemoteGlyph("Move caret right", R.drawable.ic_chevron_right, onClick = { input(RemoteRequest(edit = "right")) })
		RemoteButton("Clear TV field", onClick = { input(RemoteRequest(edit = "clear")) })
		RemoteButton("Hide TV keyboard", onClick = { input(RemoteRequest(edit = "hide")) })
		RemoteButton("Show TV keyboard", onClick = { input(RemoteRequest(edit = "show")) })
	}
}

private fun Double.displayLevel() = if (this == toInt().toDouble()) toInt().toString() else "%.1f".format(this)

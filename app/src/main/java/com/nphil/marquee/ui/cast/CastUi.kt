package com.nphil.marquee.ui.cast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nphil.marquee.R
import com.nphil.marquee.airplay.validAirPlaySecret
import com.nphil.marquee.cast.CastProvider
import com.nphil.marquee.cast.CastReceiver
import com.nphil.marquee.cast.CastTransport
import com.nphil.marquee.cast.Casting
import com.nphil.marquee.cast.castChoices
import com.nphil.marquee.container
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.settings.Heading
import com.nphil.marquee.ui.settings.SettingsActionRow
import com.nphil.marquee.ui.settings.SettingsFieldRow
import com.nphil.marquee.ui.settings.SettingsFootnote
import com.nphil.marquee.ui.settings.SettingsSwitchRow
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** Keep the current receiver through a lock, but only look for new ones while one of these pages is visible. */
@Composable
internal fun LookForCastDevices(cast: Casting, visible: Boolean = true, provider: CastProvider? = null) {
	if (!visible) return
	LifecycleResumeEffect(cast, provider) {
		val senders = provider?.let { listOf(cast[it]) } ?: cast.transports
		senders.forEach { it.beginSearch() }
		onPauseOrDispose { senders.forEach { it.endSearch() } }
	}
}

@Composable
internal fun CastSettingsPage(padding: PaddingValues, provider: CastProvider) {
	val cast = LocalContext.current.container.cast
	LookForCastDevices(cast, provider = provider)
	val sender = cast[provider]
	val devices = sender.devices
	LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		item(key = "toggle") {
			SettingsSwitchRow(0, 1, provider.label, sender.enabled, enabled = true, onChange = sender::setOptIn)
		}
		item(key = "note") {
			SettingsFootnote("Adds the Cast button to the player. This setting only applies to this device.")
		}
		if (sender.enabled) {
			item(key = "heading") { Heading("Nearby devices") }
			if (devices.isEmpty()) item(key = "status") { DiscoveryNotice(cast, listOf(sender)) }
			itemsIndexed(devices, key = { _, device -> "${device.id.provider}:${device.id.nativeId}" }) { index, receiver ->
				RouteRow(index, devices.size, R.drawable.ic_tv, receiver.name, receiver.model)
			}
		}
	}
}

@Composable
internal fun CastChooser(cast: Casting, onConnect: (CastReceiver) -> Unit, onLocal: () -> Unit, onClose: () -> Unit) {
	val previous = remember { cast.connected?.id }
	val current = cast.connected
	val pending = cast.connecting
	val nearby = cast.transports.flatMap { it.devices }
	val devices = remember(nearby, pending, current) { castChoices(nearby, pending, current) }
	LaunchedEffect(current?.id) {
		if (current != null && current.id != previous) onClose()
	}
	Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Surface(
			modifier = Modifier.padding(Space.inset).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 480.dp),
			color = lucent.canvas,
			shape = RoundedCornerShape(Radius.sheet),
		) {
			Column(Modifier.padding(Space.s3)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("Play on", style = MaterialTheme.typography.titleLarge, color = lucent.ink, modifier = Modifier.weight(1f).padding(start = Space.s3))
					IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), "Close Cast chooser", tint = lucent.ink) }
				}
				LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
					cast.pairing?.let { prompt ->
						item(key = "pairing") {
							key(pending?.id, prompt) { AirPlayPairingField(prompt.password, prompt.rejected, cast::enterSecret) }
						}
					}
					item(key = "local") {
						RouteRow(0, devices.size + 1, R.drawable.ic_movie, "This device", selected = current == null, onClick = onLocal)
					}
					itemsIndexed(devices, key = { _, device -> "${device.id.provider}:${device.id.nativeId}" }) { index, receiver ->
						RouteRow(
							index + 1, devices.size + 1, R.drawable.ic_tv, receiver.name, receiver.id.provider.label,
							selected = current?.id == receiver.id, connecting = pending?.id == receiver.id,
							onClick = if (pending == null) ({ if (current?.id == receiver.id) onClose() else onConnect(receiver) }) else null,
						)
					}
					cast.connectionProblem?.let { problem -> item(key = "problem") { ErrorLine(problem, Modifier.padding(Space.s3)) } }
					if (devices.isEmpty() || cast.transports.any { it.enabled && (it.needsPermission || it.discoveryProblem != null) }) {
						item(key = "status") { DiscoveryNotice(cast, cast.transports) }
					}
				}
			}
		}
	}
}

@Composable
private fun AirPlayPairingField(password: Boolean, rejected: Boolean, onSecret: (String) -> Unit) {
	// Pairing input is deliberately not saveable or stored with the Plex profile.
	var value by remember { mutableStateOf("") }
	val valid = validAirPlaySecret(value, password)
	val submit: () -> Unit = {
		if (valid) {
			onSecret(value)
			value = ""
		}
	}
	Column {
		val label = if (password) "Password" else "PIN"
		if (rejected) ErrorLine("The receiver rejected the $label. Try again.", Modifier.padding(Space.s3))
		SettingsFieldRow(
			0, 2, label, value, if (password) "" else "4 digits",
			onChange = { if (password || (it.length <= 4 && it.all { digit -> digit in '0'..'9' })) value = it },
			onFocus = {}, keyboardType = if (password) KeyboardType.Password else KeyboardType.NumberPassword,
			imeAction = ImeAction.Done, secret = true, onDone = submit,
		)
		SettingsActionRow(1, 2, R.drawable.ic_cast, "Connect", enabled = valid, onClick = submit)
	}
}

@Composable
private fun DiscoveryNotice(cast: Casting, senders: List<CastTransport>) {
	val enabled = senders.filter { it.enabled }
	when {
		enabled.any { it.needsPermission } -> NetworkPermission(cast)
		else -> {
			val problems = enabled.mapNotNull { it.discoveryProblem }
			if (problems.isNotEmpty()) problems.forEach { ErrorLine(it, Modifier.padding(Space.s3)) }
			else SettingsFootnote("No devices found yet. Make sure both devices are on the same Wi-Fi network.")
		}
	}
}

@Composable
private fun NetworkPermission(cast: Casting) {
	val activity = LocalActivity.current
	val context = LocalContext.current
	var requested by rememberSaveable { mutableStateOf(false) }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		requested = true
		cast.refreshDiscovery()
	}
	val canRequest = Build.VERSION.SDK_INT >= 37 &&
		(!requested || (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_LOCAL_NETWORK)))
	Column {
		SettingsFootnote("Allow local network access to find nearby receivers.")
		SettingsActionRow(0, 1, R.drawable.ic_wifi, if (canRequest) "Allow local network access" else "Open app permissions", enabled = true, onClick = {
			if (canRequest) launcher.launch(ACCESS_LOCAL_NETWORK)
			else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
		})
	}
}

@Composable
private fun RouteRow(
	index: Int, count: Int, @DrawableRes icon: Int, title: String, detail: String? = null,
	selected: Boolean = false, connecting: Boolean = false, onClick: (() -> Unit)? = null,
) {
	val state = if (connecting) "Connecting" else if (selected) "Selected" else null
	GroupRow(index, count, Modifier.semantics { state?.let { stateDescription = it } }, selected, onClick) {
		val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else lucent.ink
		Icon(painterResource(icon), contentDescription = null, tint = ink)
		Column(Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
			val description = if (connecting && detail != null) "$detail · Connecting…" else if (connecting) "Connecting…"
				else detail?.takeUnless { it.equals(title, ignoreCase = true) }
			if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = if (selected) ink else lucent.ink2)
		}
		if (connecting) CircularProgressIndicator(Modifier.size(20.dp), color = lucent.accent, strokeWidth = 2.dp)
		if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ink)
	}
}

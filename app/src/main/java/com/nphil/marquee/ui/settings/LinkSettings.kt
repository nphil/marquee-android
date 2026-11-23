package com.nphil.marquee.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.link.LinkedPhone
import com.nphil.marquee.link.LinkedPhones
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.ui.tvremote.RemoteButton
import com.nphil.marquee.ui.tvremote.RemotePanelBody
import kotlinx.coroutines.launch

/** Settings › Linked Phones (DESIGN §8.10): the switch, this phone's name, the linked phones, and the two halves of linking one. */
@Composable
internal fun LinkSettings(padding: PaddingValues) {
	val context = LocalContext.current
	val link = context.container.link
	val state by link.state.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()
	var name by remember(state.customName) { mutableStateOf(state.customName) }
	var showingCode by rememberSaveable { mutableStateOf(false) }
	var unlinking by remember { mutableStateOf<LinkedPhone?>(null) }
	var outcome by remember { mutableStateOf<LinkedPhones.Outcome?>(null) }
	var networkAllowed by remember { mutableStateOf(link.hasNetworkAccess()) }
	val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		networkAllowed = link.hasNetworkAccess()
		link.networkAccessChanged()
	}
	val readCode = rememberLauncherForActivityResult(ScanContract()) { result ->
		result.contents?.let { url -> scope.launch { outcome = link.join(url) } }
	}
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) {
			readCode.launch(scanOptions())
		} else {
			outcome = LinkedPhones.Outcome.Failed("Camera access is off. Turn it on for Marquee to scan a link code, or show this phone’s code instead.")
		}
	}
	fun scan() {
		when {
			!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
				outcome = LinkedPhones.Outcome.Failed("This device has no camera. Show its code on the other phone instead.")
			ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> readCode.launch(scanOptions())
			else -> camera.launch(Manifest.permission.CAMERA)
		}
	}
	// The name is kept when the page closes, as on the iPhone.
	DisposableEffect(link) { onDispose { link.setName(name) } }

	LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		item { SettingsSwitchRow(0, 1, "Linked Phones", state.enabled, enabled = true, onChange = link::setEnabled) }
		item {
			SettingsFootnote("Two phones with Marquee, linked once, can control each other on the same Wi-Fi: either one becomes the remote for what the other plays.")
		}
		if (state.enabled) {
			item { Heading("This phone") }
			item {
				SettingsFieldRow(0, 1, "Name", name, hint = state.name, onChange = { name = it }, onFocus = { if (!it) link.setName(name) },
					imeAction = ImeAction.Done, onDone = { link.setName(name) })
			}
			item { SettingsFootnote("The name linked phones show for this one.") }
			item { Heading("Phones") }
			itemsIndexed(state.phones, key = { _, phone -> phone.id }) { index, phone ->
				LinkedPhoneRow(index, state.phones.size + 2, phone, state.presence[phone.id], onControl = { link.control(phone.id) }) { unlinking = phone }
			}
			item(key = "show") {
				SettingsActionRow(state.phones.size, state.phones.size + 2, R.drawable.ic_qr_code, "Show Link Code", enabled = networkAllowed,
					onClick = { showingCode = true })
			}
			item(key = "scan") {
				SettingsActionRow(state.phones.size + 1, state.phones.size + 2, R.drawable.ic_qr_code_scanner, "Scan Link Code",
					enabled = networkAllowed && !state.linking, busy = state.linking, onClick = { scan() })
			}
			if (!networkAllowed) {
				item(key = "allow") {
					RemoteButton("Allow local network", onClick = { access.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }, emphasized = true,
						modifier = Modifier.fillMaxWidth())
				}
			}
			item {
				val note = state.notice
				SettingsFootnote(
					when {
						note != null -> note
						!networkAllowed -> "Marquee can’t use the local network, so it can’t find linked phones."
						state.unavailable -> "Marquee can’t look for linked phones on this network. Check that Wi-Fi is on and local network access is allowed."
						else -> "One phone shows its link code, the other scans it. Home then offers to control a linked phone while it plays."
					},
				)
			}
		}
	}

	if (showingCode) LinkCodeDialog(link, state) { showingCode = false }
	outcome?.let { result ->
		val linked = result as? LinkedPhones.Outcome.Linked
		AlertDialog(
			onDismissRequest = { outcome = null },
			title = { Text(if (linked != null) "Linked with ${linked.name}" else "Not Linked") },
			text = { Text(if (linked != null) "Both phones list each other in Linked Phones now." else (result as LinkedPhones.Outcome.Failed).message) },
			confirmButton = { TextButton(onClick = { outcome = null }) { Text("OK") } },
			containerColor = lucent.canvas,
		)
	}
	unlinking?.let { phone ->
		AlertDialog(
			onDismissRequest = { unlinking = null },
			title = { Text("Unlink ${phone.name}?") },
			text = { Text("Neither phone can control the other until they are linked again. ${phone.name} forgets this phone too when it is in reach.") },
			confirmButton = { TextButton(onClick = { link.unlink(phone.id); unlinking = null }) { Text("Unlink", color = lucent.danger) } },
			dismissButton = { TextButton(onClick = { unlinking = null }) { Text("Cancel") } },
			containerColor = lucent.canvas,
		)
	}
}

/** A linked phone, and whether it is connected and what it shows. */
@Composable
private fun LinkedPhoneRow(
	index: Int, count: Int, phone: LinkedPhone, presence: LinkedPhones.Presence?, onControl: () -> Unit, onUnlink: () -> Unit,
) {
	val status = presence?.status
	GroupRow(index, count) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(phone.name, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			Text(connection(presence), style = MaterialTheme.typography.bodySmall, color = if (presence == null) lucent.ink3 else lucent.ink2)
		}
		if (status?.isControllable == true) {
			TextButton(onClick = onControl) { Text("Control", color = lucent.accentText) }
		}
		IconButton(onClick = onUnlink) { Icon(painterResource(R.drawable.ic_delete), "Unlink ${phone.name}", tint = lucent.ink2) }
	}
}

private fun connection(presence: LinkedPhones.Presence?): String {
	if (presence == null) return "Not connected"
	val status = presence.status ?: return "Connected"
	return when (status.screen) {
		"tv" -> "Connected · Driving a TV"
		"glasses" -> "Connected · Driving XR glasses"
		else -> status.nowPlaying?.let { "Connected · Playing ${it.title}" } ?: "Connected"
	}
}

/** Show Link Code: the code the other phone scans. It is made when the dialog opens, links one phone, and is gone when it closes. */
@Composable
private fun LinkCodeDialog(link: LinkedPhones, state: LinkedPhones.State, onDismiss: () -> Unit) {
	DisposableEffect(link) {
		link.openInvite()
		onDispose { link.closeInvite() }
	}
	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		RemotePanelBody("Link Code", onDismiss) {
			val linked = state.inviteOutcome
			val shown = state.invite
			when {
				linked != null -> {
					Text("Linked with ${linked.name}", style = MaterialTheme.typography.titleMedium, color = lucent.ink)
					Text("Both phones list each other in Linked Phones now.", color = lucent.ink2)
				}
				shown != null -> {
					// A QR code is dark on light whatever the theme: scanners read it reliably only that way.
					val code = remember(shown.url) { qrCode(shown.url) }
					Image(code.asImageBitmap(), "Link code for ${shown.name}", Modifier.size(QR_SIZE).background(Color.White), filterQuality = FilterQuality.None)
					Text(shown.name, style = MaterialTheme.typography.titleMedium, color = lucent.ink, textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth())
					Text("On the other phone, open Marquee › Settings › Linked Phones › Scan Link Code. Keep both phones on the same Wi-Fi.",
						color = lucent.ink2)
				}
				state.notice != null -> Text(state.notice, color = lucent.danger)
				else -> CircularProgressIndicator(Modifier.size(Space.s6), color = lucent.accent)
			}
			RemoteButton("Done", onClick = onDismiss, emphasized = true, modifier = Modifier.fillMaxWidth())
		}
	}
}

private val QR_SIZE = 256.dp

private fun qrCode(text: String) = BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 512, 512,
	mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))

private fun scanOptions() = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
	.setPrompt("Scan the link code on the other phone").setBeepEnabled(false).setOrientationLocked(false)

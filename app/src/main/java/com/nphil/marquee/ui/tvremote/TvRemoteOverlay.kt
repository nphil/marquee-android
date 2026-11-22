package com.nphil.marquee.ui.tvremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.tvremote.RemoteRequestFailed
import com.nphil.marquee.tvremote.SavedTv
import com.nphil.marquee.tvremote.TvAdvertisement
import com.nphil.marquee.tvremote.TvListItem
import com.nphil.marquee.tvremote.TvRemote
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException

private enum class View { Face, Televisions, Pairing, More, Apps, Channels, Cameras, Keyboard }
private const val RemoteLabel = "TV Remote"

@Composable
internal fun RemoteEntryIcon(onClick: () -> Unit) {
	IconButton(onClick = onClick, modifier = Modifier.testTag("tv-remote-entry")) {
		Icon(painterResource(R.drawable.ic_tv_remote), RemoteLabel, tint = lucent.accentText)
	}
}

@Composable
internal fun TvRemoteOverlay(remote: TvRemote) {
	val state by remote.state.collectAsStateWithLifecycle()
	if (!state.opened) return
	val context = LocalContext.current
	fun hasNetwork() = Build.VERSION.SDK_INT < 37 ||
		ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
	var networkAllowed by remember { mutableStateOf(hasNetwork()) }
	var foreground by remember { mutableStateOf(false) }
	val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { networkAllowed = it }
	LifecycleStartEffect(remote) {
		foreground = true
		networkAllowed = hasNetwork()
		onStopOrDispose {
			foreground = false
			remote.networkAvailable(false)
		}
	}
	LaunchedEffect(foreground, networkAllowed) { remote.networkAvailable(foreground && networkAllowed) }

	var view by rememberSaveable { mutableStateOf(View.Face) }
	var choosing by remember { mutableStateOf<TvAdvertisement?>(null) }
	var forgetting by remember { mutableStateOf<SavedTv?>(null) }
	val invite = state.invite
	val status = state.status
	val current = state.current
	val page = when {
		invite != null || view == View.Face && current == null && networkAllowed -> View.Pairing
		view == View.Keyboard && status?.keyboard != true -> View.Face
		else -> view
	}
	val readCode = rememberLauncherForActivityResult(ScanContract()) { result ->
		result.contents?.let(remote::openInvite)
	}
	val scan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) readCode.launch(scanOptions()) else remote.report("Camera access is off. Paste the TV’s pairing link or use its code.")
	}
	val scanQr = {
		when {
			!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
				remote.report("This device has no camera. Paste the pairing link or use the TV’s code.")
			ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> readCode.launch(scanOptions())
			else -> scan.launch(Manifest.permission.CAMERA)
		}
	}
	val back = {
		if (!state.busy) {
			remote.clearNotice()
			when {
				invite != null -> { remote.dismissInvite(); view = View.Face }
				choosing != null -> choosing = null
				page in listOf(View.Apps, View.Channels, View.Cameras) -> view = View.More
				page == View.Pairing && current == null -> remote.close()
				view != View.Face -> view = View.Face
				else -> remote.close()
			}
		}
	}
	LaunchedEffect(status?.ready, status?.keyboard) {
		if (status?.keyboard != true || !status.ready) if (view == View.Keyboard) view = View.Face
	}
	val title = when (page) {
		View.Face -> RemoteLabel
		View.Pairing -> "Pair a TV"
		View.Televisions -> "TVs"
		View.More -> "More controls"
		View.Apps -> "Apps"
		View.Channels -> "Channels"
		View.Cameras -> "Cameras"
		View.Keyboard -> "TV keyboard"
	}
	val canGoBack = view != View.Face || invite != null || choosing != null
	RemotePanel(title, back, backIcon = canGoBack, busy = state.busy) {
		val notice = state.notice
		if (notice != null) {
			Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = lucent.danger,
				style = MaterialTheme.typography.bodyMedium)
		}
		if (!networkAllowed) {
			Text("Allow local network access to find and control a LumaTV.", color = lucent.ink2)
			RemoteButton("Allow local network", onClick = { access.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }, emphasized = true)
			return@RemotePanel
		}
		if (!state.loaded) {
			CircularProgressIndicator(Modifier.size(Space.s6), color = lucent.accent)
			return@RemotePanel
		}
		when (page) {
			View.Face -> RemoteFace(current, status, remote::input, remote::power,
				onTvs = { view = View.Televisions }, onPair = { view = View.Pairing },
				onKeyboard = { view = View.Keyboard }, onMore = { view = View.More }, onReconnect = remote::reconnect)
			View.Pairing -> {
				if (invite != null) {
					PairForm("Pair with the TV at ${invite.host}", state.phoneName, state.busy, typedCode = false) { _, name ->
						remote.pair(invite, name) { view = View.Face }
					}
				} else {
					val selected = choosing
					if (selected != null) {
						PairForm(selected.name, state.phoneName, state.busy, typedCode = true) { code, name ->
							remote.pair(selected, code, name) { choosing = null; view = View.Face }
						}
					} else {
						PairChoices(state, scanQr, remote::openInvite) { choosing = it; remote.clearNotice() }
					}
				}
			}
			View.Televisions -> {
				for (tv in state.paired) {
					Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						RemoteButton(tv.name, onClick = { remote.select(tv); view = View.Face },
							emphasized = tv.id == state.selected, modifier = Modifier.weight(1f))
						IconButton(onClick = { forgetting = tv }) {
							Icon(painterResource(R.drawable.ic_delete), "Forget ${tv.name}", tint = lucent.ink2)
						}
					}
				}
				RemoteButton("Pair another TV", onClick = { view = View.Pairing })
			}
			View.Keyboard -> if (status != null) RemoteKeyboard(status, remote::input)
			View.More -> RemoteMore(status, remote::input, { kind ->
				view = when (kind) { "apps" -> View.Apps; "channels" -> View.Channels; else -> View.Cameras }
			}) { action ->
				remote.perform(action) { if (action.action != "volume") view = View.Face }
			}
			View.Apps, View.Channels, View.Cameras -> {
				val (kind, action) = when (page) {
					View.Apps -> "apps" to "launch"
					View.Channels -> "channels" to "tune"
					else -> "cameras" to "camera"
				}
				RemoteList(kind, status?.ready == true, remote::list) { item ->
					remote.perform(RemoteRequest(action = action, item = item.id)) { view = View.Face }
				}
			}
		}
	}
	forgetting?.let { tv ->
		AlertDialog(
			onDismissRequest = { forgetting = null }, title = { Text("Forget ${tv.name}?") },
			text = { Text("The pairing key is removed from this device. The TV keeps this device in its own list until it is removed there.") },
			confirmButton = { TextButton(onClick = { remote.forget(tv); forgetting = null }) { Text("Forget", color = lucent.danger) } },
			dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
			containerColor = lucent.canvas,
		)
	}
}

@Composable
private fun RemotePanel(title: String, onBack: () -> Unit, backIcon: Boolean, busy: Boolean, content: @Composable () -> Unit) {
	Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		RemotePanelBody(title, onBack, backIcon, busy, content)
	}
}

@Composable
internal fun RemotePanelBody(title: String, onBack: () -> Unit, backIcon: Boolean = false, busy: Boolean = false, content: @Composable () -> Unit) {
	Surface(
		modifier = Modifier.padding(Space.inset).widthIn(max = 548.dp).fillMaxWidth().testTag("tv-remote-panel"),
		shape = RoundedCornerShape(Radius.sheet), color = lucent.sheet, contentColor = lucent.ink,
		border = BorderStroke(1.dp, lucent.edge),
	) {
		Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
					maxLines = 2, overflow = TextOverflow.Ellipsis)
				IconButton(onClick = onBack, enabled = !busy) {
					Icon(painterResource(if (backIcon) R.drawable.ic_arrow_back else R.drawable.ic_close),
						if (backIcon) "Back" else "Close remote", tint = lucent.ink2)
				}
			}
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) { content() }
		}
	}
}

@Composable
private fun PairChoices(state: TvRemote.State, onScan: () -> Unit, onLink: (String) -> Unit, onTv: (TvAdvertisement) -> Unit) {
	var paste by remember { mutableStateOf(false) }
	var link by remember { mutableStateOf("") }
	Text("Open pairing in LumaTV’s Phone Remote settings.", color = lucent.ink2)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		RemoteButton("Scan QR code", onClick = onScan, emphasized = true)
		RemoteButton("Paste pairing link", onClick = { paste = !paste })
	}
	if (paste) {
		OutlinedTextField(link, { link = it }, label = { Text("Pairing link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
			visualTransformation = PasswordVisualTransformation())
		RemoteButton("Check link", enabled = link.isNotBlank(), onClick = { onLink(link.trim()); link = "" })
	}
	RemoteSectionLabel("Nearby TVs")
	if (state.discovery.televisions.isEmpty()) {
		Text(if (state.discovery.failed) "TV discovery is unavailable. Use the QR code or its link."
			else "Looking for LumaTV on this network…", color = lucent.ink2, style = MaterialTheme.typography.bodyMedium)
	}
	for (tv in state.discovery.televisions) RemoteButton(tv.name, onClick = { onTv(tv) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PairForm(title: String, name: String, busy: Boolean, typedCode: Boolean, onPair: (String, String) -> Unit) {
	var phoneName by remember(title) { mutableStateOf(name) }
	var code by remember(title) { mutableStateOf("") }
	Text(title, style = MaterialTheme.typography.titleMedium)
	OutlinedTextField(phoneName, { phoneName = it }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
		label = { Text("Device name") })
	if (typedCode) {
		OutlinedTextField(code, { code = it }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
			label = { Text("TV pairing code") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
	}
	Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
		RemoteButton("Pair TV", enabled = !busy && phoneName.isNotBlank() && (!typedCode || code.isNotBlank()),
			emphasized = true, onClick = { onPair(code, phoneName) })
		if (busy) CircularProgressIndicator(Modifier.size(Space.s6), color = lucent.accent)
	}
}

@Composable
private fun RemoteList(kind: String, enabled: Boolean, load: suspend (String) -> List<TvListItem>, select: (TvListItem) -> Unit) {
	var items by remember(kind) { mutableStateOf<List<TvListItem>?>(null) }
	var error by remember(kind) { mutableStateOf<String?>(null) }
	var attempt by remember(kind) { mutableStateOf(0) }
	var query by rememberSaveable(kind) { mutableStateOf("") }
	LaunchedEffect(enabled, kind, attempt) {
		if (!enabled) return@LaunchedEffect
		error = null
		try { items = load(kind) }
		catch (e: CancellationException) { throw e }
		catch (e: Exception) { error = (e as? RemoteRequestFailed)?.message ?: "Couldn’t get the list from the TV." }
	}
	val loaded = items
	when {
		!enabled -> Text("Not connected to the TV.", color = lucent.ink2)
		error != null -> { Text(error.orEmpty(), color = lucent.danger); RemoteButton("Try again", { attempt++ }) }
		loaded == null -> CircularProgressIndicator(Modifier.size(Space.s6), color = lucent.accent)
		loaded.isEmpty() -> Text("The TV has no $kind to show.", color = lucent.ink2)
		else -> {
			OutlinedTextField(query, { query = it }, label = { Text("Find $kind") }, singleLine = true, modifier = Modifier.fillMaxWidth())
			for (item in loaded) if (item.label.contains(query.trim(), ignoreCase = true) || item.number == query.trim()) {
				RemoteButton(listOfNotNull(item.number, item.label).joinToString(" · "), { select(item) }, modifier = Modifier.fillMaxWidth())
			}
		}
	}
}

private fun scanOptions() = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
	.setPrompt("Scan the pairing code on the TV").setBeepEnabled(false).setOrientationLocked(false)

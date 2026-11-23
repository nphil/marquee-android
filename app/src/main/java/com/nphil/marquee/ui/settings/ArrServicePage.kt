package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.core.ArrAddress
import com.nphil.marquee.core.ArrConnection
import com.nphil.marquee.core.ArrKind
import com.nphil.marquee.core.ArrLink
import com.nphil.marquee.core.arrAddress
import com.nphil.marquee.core.testArr
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ArrServicePage(kind: ArrKind, contentPadding: PaddingValues) {
	val container = LocalContext.current.container
	val model = viewModel(key = "arrService.${kind.name}") { ArrServiceEditor(kind, container) }
	if (!model.loaded) return
	var editingAddress by remember { mutableStateOf(false) }
	var editingKey by remember { mutableStateOf(false) }
	var forgetting by rememberSaveable { mutableStateOf(false) }
	val focus = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current
	val fieldProblem = when (val problem = model.parsed?.problem) {
		"key" -> problem.takeUnless { model.key.isBlank() || editingKey }
		else -> problem?.takeUnless { model.address.isBlank() || editingAddress }
	}
	val test = {
		focus.clearFocus()
		keyboard?.hide()
		model.test()
	}
	LazyColumn(
		modifier = Modifier.fillMaxSize().imePadding(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "fields") {
			Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
				SettingsFieldRow(
					0, 2, "Address", model.address, "https://${kind.title.lowercase()}.local",
					onChange = model::editAddress, onFocus = { editingAddress = it }, keyboardType = KeyboardType.Uri,
					imeAction = ImeAction.Next, onDone = test,
				)
				SettingsFieldRow(
					1, 2, "API Key", model.key, "Required", onChange = model::editKey, onFocus = { editingKey = it },
					imeAction = ImeAction.Done, secret = true, onDone = test,
				)
			}
		}
		item(key = "instructions") {
			val inset = Modifier.padding(top = Space.s2, bottom = Space.s4)
			if (fieldProblem != null) {
				ErrorLine(addressProblem(fieldProblem), inset.padding(horizontal = Space.s4))
			} else {
				SettingsFootnote(
					"Marquee removes a deleted ${kind.noun} from ${kind.title}, with its files, and keeps it from being added again. " +
						"Copy the key from ${kind.title} › Settings › General › Security.",
					inset,
				)
			}
		}
		item(key = "test") {
			SettingsActionRow(
				0, 1, R.drawable.ic_wifi, "Test Connection", enabled = model.parsed?.address != null && !model.testing,
				onClick = test, busy = model.testing,
			)
		}
		item(key = "status") {
			val inset = Modifier.padding(top = Space.s2, bottom = Space.s4).semantics { liveRegion = LiveRegionMode.Polite }
			val result = model.result
			val error = model.saveProblem ?: result?.problem?.let { connectionProblem(kind, it) }
			if (error != null) {
				ErrorLine(error, inset.padding(horizontal = Space.s4))
			} else {
				val version = result?.version
				SettingsFootnote(
					when {
						model.testing -> "Checking ${kind.title}…"
						version != null -> "Connected · ${kind.title} $version"
						model.saved -> "Not tested yet."
						else -> "Not set up. The key stays on this device."
					},
					inset,
				)
			}
		}
		if (model.saved) item(key = "forget") {
			SettingsActionRow(0, 1, R.drawable.ic_cloud_off, "Forget This Key", enabled = true, tint = lucent.danger, onClick = { forgetting = true })
		}
	}
	if (forgetting) {
		AlertDialog(
			onDismissRequest = { forgetting = false },
			title = { Text("Forget ${kind.title}?") },
			text = { Text("The address and API key are removed from this device.") },
			confirmButton = {
				TextButton(
					onClick = { forgetting = false; model.forget() },
					colors = ButtonDefaults.textButtonColors(contentColor = lucent.danger),
				) { Text("Forget") }
			},
			dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
			containerColor = lucent.canvas,
		)
	}
}

private class ArrServiceEditor(private val kind: ArrKind, private val container: AppContainer) : ViewModel() {
	var loaded by mutableStateOf(false)
		private set
	var address by mutableStateOf("")
		private set
	var key by mutableStateOf("")
		private set
	var parsed by mutableStateOf<ArrAddress?>(null)
		private set
	var saved by mutableStateOf(false)
		private set
	var testing by mutableStateOf(false)
		private set
	var result by mutableStateOf<ArrConnection?>(null)
		private set
	var saveProblem by mutableStateOf<String?>(null)
		private set
	private var edit: Job? = null
	private var check: Job? = null

	init {
		viewModelScope.launch {
			container.arrServices.link(kind)?.let { link -> address = link.address; key = link.apiKey }
			saved = address.isNotEmpty()
			parsed = if (saved) ArrAddress(address = address) else null
			loaded = true
		}
	}

	fun editAddress(value: String) { address = value; changed() }
	fun editKey(value: String) { key = value; changed() }

	private fun changed() {
		reset()
		edit = viewModelScope.launch {
			if (address.isNotBlank()) {
				val draftKey = key.trim()
				try {
					val value = container.core.arrAddress(kind, address, draftKey)
					parsed = value
					value.address?.let { keep(it, draftKey) }
				} catch (e: CancellationException) {
					throw e
				} catch (_: Exception) {
					saveProblem = "Couldn't check this address. Try again."
				}
			}
		}
	}

	/** Valid drafts take effect immediately; a test is an explicit read-only check. */
	fun test() {
		if (testing) return
		val testingKey = key.trim()
		val saving = edit
		testing = true
		check = viewModelScope.launch {
			try {
				saving?.join()
				val testingAddress = parsed?.address
				if (testingAddress != null && keep(testingAddress, testingKey)) {
					result = container.core.testArr(kind, testingAddress, testingKey)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				result = ArrConnection(problem = "unreachable")
			}
			testing = false
		}
	}

	fun forget() {
		reset()
		address = ""
		key = ""
		edit = viewModelScope.launch { keep(null, "") }
	}

	private fun reset() {
		edit?.cancel()
		check?.cancel()
		parsed = null
		result = null
		saveProblem = null
		testing = false
	}

	private suspend fun keep(address: String?, key: String): Boolean = try {
		container.arrServices.save(kind, address?.let { ArrLink(it, key) })
		saved = address != null
		saveProblem = null
		true
	} catch (e: CancellationException) {
		throw e
	} catch (_: Exception) {
		saveProblem = "Couldn't save ${kind.title} on this device."
		false
	}
}

private val ArrKind.noun get() = if (this == ArrKind.Radarr) "movie" else "show"

private fun addressProblem(problem: String): String = when (problem) {
	"scheme" -> "The address must start with https:// or http://."
	"plaintext" -> "Use https:// for addresses outside your home network."
	"key" -> "That is not a valid API key."
	"notOrigin" -> "Enter the address alone, without a sign-in, path, query or fragment."
	else -> "That is not a valid address."
}

private fun connectionProblem(kind: ArrKind, problem: String): String = when (problem) {
	"badKey" -> "${kind.title} refused the API key. Copy it again from ${kind.title}."
	"notArr" -> "That address does not look like ${kind.title}."
	"timedOut" -> "${kind.title} took too long to answer."
	else -> "Can't reach ${kind.title}. Check the address and network."
}

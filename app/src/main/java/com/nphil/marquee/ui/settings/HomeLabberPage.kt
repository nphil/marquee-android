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
import com.nphil.marquee.core.HomeLabberAddress
import com.nphil.marquee.core.HomeLabberConnection
import com.nphil.marquee.core.HomeLabberLink
import com.nphil.marquee.core.homeLabberAddress
import com.nphil.marquee.core.testHomeLabber
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings › Integrations › HomeLabber Quality Map: an address and a read-only token, a connection test and Forget.
 * A saved address and token are what turn it on; there is no separate switch.
 */
@Composable
internal fun HomeLabberPage(contentPadding: PaddingValues) {
	val container = LocalContext.current.container
	val model = viewModel(key = "homeLabber") { HomeLabberEditor(container) }
	if (!model.loaded) return
	var editingAddress by remember { mutableStateOf(false) }
	var editingToken by remember { mutableStateOf(false) }
	var forgetting by rememberSaveable { mutableStateOf(false) }
	val focus = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current
	// A problem shows once its field is left, not while it is being typed.
	val fieldProblem = model.parsed?.problem?.let { problem ->
		if (problem == "token") problem.takeUnless { model.token.isBlank() || editingToken }
		else problem.takeUnless { model.address.isBlank() || editingAddress }
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
					0, 2, "Address", model.address, "https://homelabber.local",
					onChange = model::editAddress, onFocus = { editingAddress = it }, keyboardType = KeyboardType.Uri,
				)
				SettingsFieldRow(
					1, 2, "Token", model.token, "Required", onChange = model::editToken, onFocus = { editingToken = it },
					imeAction = ImeAction.Done, secret = true, onDone = test,
				)
			}
		}
		item(key = "instructions") {
			val inset = Modifier.padding(top = Space.s2, bottom = Space.s4).semantics { liveRegion = LiveRegionMode.Polite }
			val error = model.saveProblem ?: fieldProblem?.let(HomeLabberText::address)
			if (error != null) ErrorLine(error, inset.padding(horizontal = Space.s4)) else SettingsFootnote(HomeLabberText.INTRO, inset)
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
			val failure = result?.problem?.let(HomeLabberText::problem)
			if (failure != null) {
				ErrorLine(failure, inset.padding(horizontal = Space.s4))
			} else {
				SettingsFootnote(
					when {
						!model.saved -> HomeLabberText.NOT_SET_UP
						model.testing -> "Checking… Asking HomeLabber how far it has measured."
						result != null -> HomeLabberText.connection(result)
						else -> "Not tested yet."
					},
					inset,
				)
			}
		}
		if (model.saved) item(key = "forget") {
			SettingsActionRow(0, 1, R.drawable.ic_cloud_off, "Forget This Token", enabled = true, tint = lucent.danger, onClick = { forgetting = true })
		}
	}
	if (forgetting) {
		AlertDialog(
			onDismissRequest = { forgetting = false },
			title = { Text("Forget HomeLabber?") },
			text = { Text(HomeLabberText.FORGET_MESSAGE) },
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

private class HomeLabberEditor(private val container: AppContainer) : ViewModel() {
	var loaded by mutableStateOf(false)
		private set
	var address by mutableStateOf("")
		private set
	var token by mutableStateOf("")
		private set
	var parsed by mutableStateOf<HomeLabberAddress?>(null)
		private set
	var saved by mutableStateOf(false)
		private set
	var testing by mutableStateOf(false)
		private set
	var result by mutableStateOf<HomeLabberConnection?>(null)
		private set
	var saveProblem by mutableStateOf<String?>(null)
		private set
	private var edit: Job? = null
	private var check: Job? = null

	init {
		viewModelScope.launch {
			container.homeLabber.current()?.let { link -> address = link.address; token = link.token }
			saved = address.isNotEmpty()
			parsed = if (saved) HomeLabberAddress(address = address) else null
			loaded = true
		}
	}

	fun editAddress(value: String) { address = value; changed() }
	fun editToken(value: String) { token = value; changed() }

	private fun changed() {
		reset()
		if (address.isBlank() && token.isBlank()) return
		edit = viewModelScope.launch {
			val draftToken = token.trim()
			try {
				val value = container.core.homeLabberAddress(address, draftToken)
				parsed = value
				value.address?.let { keep(it, draftToken) }
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				saveProblem = "Couldn't check this address. Try again."
			}
		}
	}

	/** A valid draft takes effect at once, and is what the downloads ask HomeLabber with; a test is a read-only check. */
	fun test() {
		if (testing) return
		val testingToken = token.trim()
		val saving = edit
		testing = true
		check = viewModelScope.launch {
			try {
				saving?.join()
				val testingAddress = parsed?.address
				if (testingAddress != null && keep(testingAddress, testingToken)) {
					result = container.core.testHomeLabber(testingAddress, testingToken)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Diag.warning(DiagCategory.Integration, "homelabber.test.failed", "error" to e.javaClass.simpleName)
				result = HomeLabberConnection(problem = "unreachable")
			}
			testing = false
		}
	}

	fun forget() {
		reset()
		address = ""
		token = ""
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

	private suspend fun keep(address: String?, token: String): Boolean = try {
		container.homeLabber.save(address?.let { HomeLabberLink(it, token) })
		saved = address != null
		saveProblem = null
		true
	} catch (e: CancellationException) {
		throw e
	} catch (_: Exception) {
		saveProblem = "Couldn't save HomeLabber on this device."
		false
	}
}

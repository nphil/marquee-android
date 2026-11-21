package com.nphil.marquee.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.diagnostics.DiagCheck
import com.nphil.marquee.diagnostics.DiagConfig
import com.nphil.marquee.diagnostics.DiagDraft
import com.nphil.marquee.diagnostics.DiagField
import com.nphil.marquee.diagnostics.DiagParse
import com.nphil.marquee.diagnostics.DiagStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Diagnostic Logs' state: the draft being typed, what the bridge makes of it, the connection test and what live
 * sending is doing. A valid draft is saved half a second after the last key, as the iPhone saves on change.
 */
class DiagnosticsModel : ViewModel() {
	private val mutableDraft = MutableStateFlow(DiagDraft())
	private val mutableParse = MutableStateFlow<DiagParse?>(null)
	private val mutableName = MutableStateFlow("")
	private val mutableCheck = MutableStateFlow<DiagCheck>(DiagCheck.Untested)
	private val mutableStatus = MutableStateFlow<DiagStatus?>(null)
	private var saving: Job? = null
	private var naming: Job? = null

	val config: StateFlow<DiagConfig?> = Diag.settings.config
	val draft: StateFlow<DiagDraft> = mutableDraft.asStateFlow()
	val parse: StateFlow<DiagParse?> = mutableParse.asStateFlow()
	val name: StateFlow<String> = mutableName.asStateFlow()
	val check: StateFlow<DiagCheck> = mutableCheck.asStateFlow()
	val status: StateFlow<DiagStatus?> = mutableStatus.asStateFlow()

	init {
		viewModelScope.launch {
			val saved = Diag.settings.load()
			mutableDraft.value = DiagDraft(saved.server, saved.topic, saved.token)
			mutableName.value = saved.name
			mutableParse.value = Diag.parse(mutableDraft.value)
		}
	}

	fun edit(field: DiagField, value: String) {
		mutableDraft.update { it.with(field, value) }
		saving?.cancel()
		saving = viewModelScope.launch {
			val draft = mutableDraft.value
			val parsed = Diag.parse(draft)
			mutableParse.value = parsed
			if (parsed.problem == null) {
				delay(SAVE_MILLIS)
				save(draft)
			}
		}
	}

	fun editName(value: String) {
		mutableName.value = value
		naming?.cancel()
		naming = viewModelScope.launch {
			delay(SAVE_MILLIS)
			Diag.update { it.copy(name = value.trim()) }
		}
	}

	fun setLive(on: Boolean) = change("live", on) { it.copy(live = on) }

	fun setDetailed(on: Boolean) = change("detailed", on) { it.copy(detailed = on) }

	fun setButton(on: Boolean) = change("button", on) { it.copy(button = on) }

	/** Saves what is typed, then sends a test message to it; the result shows under the buttons. */
	fun test() {
		val typed = mutableDraft.value
		viewModelScope.launch {
			val parsed = Diag.parse(typed)
			mutableParse.value = parsed
			if (parsed.problem != null || mutableCheck.value == DiagCheck.Testing) return@launch
			saving?.cancel()
			save(typed)
			mutableCheck.value = DiagCheck.Testing
			mutableCheck.value = DiagCheck.Done(Diag.test())
			refresh()
		}
	}

	fun forget() {
		saving?.cancel()
		viewModelScope.launch {
			Diag.update { it.copy(server = "", topic = "", token = "") }
			mutableDraft.value = DiagDraft()
			mutableParse.value = null
			mutableCheck.value = DiagCheck.Untested
			Diag.notice(DiagCategory.Integration, "ntfy.forget")
		}
	}

	suspend fun refresh() {
		mutableStatus.value = Diag.status()
	}

	private suspend fun save(draft: DiagDraft) {
		val server = draft.server.trim()
		val topic = draft.topic.trim()
		val token = draft.token.trim()
		val saved = Diag.settings.load()
		if (saved.server == server && saved.topic == topic && saved.token == token) return
		Diag.update { it.copy(server = server, topic = topic, token = token) }
		mutableCheck.value = DiagCheck.Untested
		Diag.info(DiagCategory.Integration, "ntfy.save", "token" to token.isNotEmpty())
	}

	private fun change(key: String, value: Boolean, edit: (DiagConfig) -> DiagConfig) {
		viewModelScope.launch {
			Diag.update(edit)
			Diag.info(DiagCategory.Ui, "setting", "key" to key, "value" to value)
		}
	}

	private companion object {
		const val SAVE_MILLIS = 500L
	}
}

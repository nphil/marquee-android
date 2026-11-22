package com.nphil.marquee.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nphil.marquee.core.SeerrParsed
import com.nphil.marquee.seerr.Seerr
import com.nphil.marquee.seerr.SeerrText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is typed on Seerr's page; [plex] signs in with the Plex profile, otherwise with [key]. */
@Immutable
data class SeerrDraft(val address: String = "", val key: String = "", val plex: Boolean = true) {
	/** The key to save: none when the Plex profile signs in. */
	val savedKey: String? get() = if (plex) null else key.trim()

	/** Whether there is enough to ask Core about: an address, and the key when one signs in with it. */
	val complete: Boolean get() = address.isNotBlank() && (plex || key.isNotBlank())
}

/**
 * Seerr's settings page: the draft being typed, what Core makes of it and the connection test. A draft Core accepts is
 * saved 400 ms after the last key; Test Connection saves it at once first.
 */
class SeerrPageModel(private val seerr: Seerr) : ViewModel() {
	private val mutableDraft = MutableStateFlow(SeerrDraft())
	private val mutableParse = MutableStateFlow<SeerrParsed?>(null)
	private val mutableCheck = MutableStateFlow<SeerrText.Check>(SeerrText.Check.Untested)
	private var saving: Job? = null
	private var testing: Job? = null

	val draft: StateFlow<SeerrDraft> = mutableDraft.asStateFlow()
	val parse: StateFlow<SeerrParsed?> = mutableParse.asStateFlow()
	val check: StateFlow<SeerrText.Check> = mutableCheck.asStateFlow()
	val config: StateFlow<Seerr.Config> = seerr.config

	init {
		viewModelScope.launch {
			val saved = seerr.config.first { it.loaded }
			val draft = SeerrDraft(saved.address, saved.apiKey.orEmpty(), plex = saved.apiKey == null)
			mutableDraft.value = draft
			mutableParse.value = parse(draft)
		}
	}

	fun editAddress(address: String) = edit { it.copy(address = address) }

	fun editKey(key: String) = edit { it.copy(key = key) }

	fun setPlex(plex: Boolean) = edit { it.copy(plex = plex) }

	private fun edit(change: (SeerrDraft) -> SeerrDraft) {
		mutableDraft.update(change)
		mutableCheck.value = SeerrText.Check.Untested
		testing?.cancel()
		saving?.cancel()
		saving = viewModelScope.launch {
			val draft = mutableDraft.value
			val parsed = parse(draft)
			mutableParse.value = parsed
			if (parsed.problem == null && draft.complete) {
				delay(SAVE_MILLIS)
				save(draft, parsed)
			}
		}
	}

	private suspend fun parse(draft: SeerrDraft): SeerrParsed =
		if (draft.address.isBlank()) SeerrParsed() else seerr.parse(draft.address, draft.savedKey)

	private suspend fun save(draft: SeerrDraft, parsed: SeerrParsed) {
		seerr.save(parsed.address ?: draft.address.trim(), draft.savedKey)
	}

	fun setOn(on: Boolean) {
		viewModelScope.launch { seerr.setOn(on) }
	}

	fun setPulsarr(on: Boolean) {
		viewModelScope.launch { seerr.setPulsarr(on) }
	}

	/** Saves what is typed, then signs in to Seerr and says who this is. */
	fun test() {
		val draft = mutableDraft.value
		if (!draft.complete || mutableCheck.value == SeerrText.Check.Testing) return
		saving?.cancel()
		mutableCheck.value = SeerrText.Check.Testing
		testing = viewModelScope.launch {
			val parsed = parse(draft)
			mutableParse.value = parsed
			if (parsed.problem != null) {
				mutableCheck.value = SeerrText.Check.Failed(SeerrText.problem(parsed.problem).orEmpty())
				return@launch
			}
			mutableCheck.value = try {
				save(draft, parsed)
				val found = seerr.test()
				SeerrText.Check.Connected(found.version, found.user)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				SeerrText.Check.Failed(SeerrText.failure(e))
			}
		}
	}

	/** Removes everything Seerr has here; the page starts over empty. */
	fun forget() {
		saving?.cancel()
		testing?.cancel()
		viewModelScope.launch {
			seerr.forget()
			mutableDraft.value = SeerrDraft()
			mutableParse.value = null
			mutableCheck.value = SeerrText.Check.Untested
		}
	}

	private companion object {
		const val SAVE_MILLIS = 400L
	}
}

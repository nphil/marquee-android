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
 * tested [PAUSE_MILLIS] after the last key, or at once on Done or Test Connection. The test saves the draft, and a
 * success is what makes Requests appear. An address typed without a scheme is tried as https, then as http.
 */
class SeerrPageModel(private val seerr: Seerr) : ViewModel() {
	private val mutableDraft = MutableStateFlow(SeerrDraft())
	private val mutableParse = MutableStateFlow<SeerrParsed?>(null)
	private val mutableCheck = MutableStateFlow<SeerrText.Check>(SeerrText.Check.Untested)
	private var pausing: Job? = null
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
			if (saved.verified) mutableCheck.compareAndSet(SeerrText.Check.Untested, SeerrText.Check.Saved)
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
		pausing?.cancel()
		pausing = viewModelScope.launch {
			val draft = mutableDraft.value
			val parsed = parse(draft)
			mutableParse.value = parsed
			if (parsed.problem == null && draft.complete) {
				delay(PAUSE_MILLIS)
				run(draft)
			}
		}
	}

	/** Core's verdict on the address as it will first be tried. */
	private suspend fun parse(draft: SeerrDraft): SeerrParsed =
		if (draft.address.isBlank()) SeerrParsed() else seerr.parse(SeerrText.candidates(draft.address).first(), draft.savedKey)

	/** Turning Seerr on tests what is already here, if it has not been shown to work; Requests then appears by itself. */
	fun setOn(on: Boolean) {
		viewModelScope.launch {
			seerr.setOn(on)
			if (on && !seerr.config.value.verified) test()
		}
	}

	fun setPulsarr(on: Boolean) {
		viewModelScope.launch { seerr.setPulsarr(on) }
	}

	/** Saves what is typed, then signs in to Seerr and says who this is. */
	fun test() {
		if (mutableCheck.value == SeerrText.Check.Testing) return
		pausing?.cancel()
		run(mutableDraft.value)
	}

	private fun run(draft: SeerrDraft) {
		if (!draft.complete) return
		mutableCheck.value = SeerrText.Check.Testing
		testing = viewModelScope.launch { mutableCheck.value = connect(draft) }
	}

	private suspend fun connect(draft: SeerrDraft): SeerrText.Check {
		val tries = SeerrText.candidates(draft.address)
		var shown: Throwable? = null
		var first: String? = null
		var saved: String? = null
		var answered = false
		for ((position, candidate) in tries.withIndex()) {
			val parsed = seerr.parse(candidate, draft.savedKey)
			if (position == 0) {
				mutableParse.value = parsed
				first = parsed.address ?: candidate
			}
			if (parsed.problem != null) {
				// http is refused for an address off the home network; the https failure is the one to tell.
				if (position == 0) return SeerrText.Check.Failed(SeerrText.problem(parsed.problem).orEmpty())
				break
			}
			try {
				saved = parsed.address ?: candidate
				seerr.save(saved, draft.savedKey)
				val found = seerr.test()
				seerr.setVerified(true)
				return SeerrText.Check.Connected(found.version, found.user)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				val other = SeerrText.triesOtherScheme(e)
				if (shown == null || !other) shown = e
				if (!other) {
					answered = true
					break
				}
			}
		}
		if (!answered && first != null && saved != first) {
			// Neither scheme answered: keep the first address, which is the one to fix.
			seerr.save(first, draft.savedKey)
		}
		seerr.setVerified(false)
		return SeerrText.Check.Failed(SeerrText.failure(shown ?: IllegalStateException()))
	}

	/** Removes everything Seerr has here; the page starts over empty. */
	fun forget() {
		pausing?.cancel()
		testing?.cancel()
		viewModelScope.launch {
			seerr.forget()
			mutableDraft.value = SeerrDraft()
			mutableParse.value = null
			mutableCheck.value = SeerrText.Check.Untested
		}
	}

	private companion object {
		const val PAUSE_MILLIS = 900L
	}
}

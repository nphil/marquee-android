package com.nphil.marquee.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the player is asked to play. It is saved with the dock, so what was playing comes back after the app restarts. */
@Serializable
internal sealed interface PlayRequest {
	/**
	 * A title. [fromStart] plays from the beginning instead of resuming. [titleKey] files the audio and subtitle choices
	 * under the show for an episode; null files them under [ratingKey]. A [downloadId] plays the saved copy and a
	 * [recordingMediaID] a DVR recording.
	 */
	@Serializable
	data class Title(
		val ratingKey: String,
		val fromStart: Boolean = false,
		val titleKey: String? = null,
		val downloadId: String? = null,
		val recordingMediaID: Int? = null,
	) : PlayRequest

	/** A Live TV channel of the guide named by [scope]. */
	@Serializable
	data class Channel(val scope: String, val channel: String) : PlayRequest
}

internal fun PlayRequest.encoded(): String = Json.encodeToString(PlayRequest.serializer(), this)

/** The request saved as [text], or null when there is none or it no longer reads. */
internal fun decodedPlayRequest(text: String?): PlayRequest? =
	text?.let { runCatching { Json.decodeFromString(PlayRequest.serializer(), it) }.getOrNull() }

/** One run of the player: [id] tells it from the next title that replaces it, so each gets its own models. */
internal data class DockSession(val id: Long, val request: PlayRequest)

/**
 * The app's one player. It sits above the back stack, so pages come and go over a title that goes on playing: full
 * screen, as a bar above the tabs, or closed. Each [DockSession] has its own store of view models (the player's), cleared
 * when the session's screen leaves composition and with the dock itself; the same ExoPlayer and picture serve the
 * full screen and the bar, so minimising and expanding never reload.
 */
@Stable
internal class PlayerDock(private val saved: SavedStateHandle) : ViewModel() {
	var state by mutableStateOf(DockState.Closed)
		private set

	var session by mutableStateOf<DockSession?>(null)
		private set

	private var issued = 0L
	private val stores = HashMap<Long, ViewModelStore>()

	init {
		// After the process was killed the title comes back full screen, where the player was left.
		decodedPlayRequest(saved.get<String>(SAVED))?.let(::open)
	}

	/** Plays [request] full screen. The same request already open only returns to full screen, it does not restart. */
	fun open(request: PlayRequest) {
		if (session?.request == request) {
			state = state.after(DockEvent.Expand)
			return
		}
		session = DockSession(++issued, request)
		state = state.after(DockEvent.Open)
		saved[SAVED] = request.encoded()
	}

	fun minimise() {
		state = state.after(DockEvent.Minimise)
	}

	fun expand() {
		state = state.after(DockEvent.Expand)
	}

	/**
	 * Closes the player. With [id] it closes only that session, so a screen that is already leaving cannot take away the
	 * one that replaced it.
	 */
	fun close(id: Long? = null) {
		if (id != null && session?.id != id) return
		state = state.after(DockEvent.Close)
		session = null
		saved.remove<String>(SAVED)
	}

	/** The owner of the view models of session [id]: the player's model lives and dies with it. */
	fun owner(id: Long): ViewModelStoreOwner = Owner(stores.getOrPut(id) { ViewModelStore() })

	/** Called once the screen of session [id] is gone: this stops what it played. */
	fun release(id: Long) {
		stores.remove(id)?.clear()
	}

	override fun onCleared() {
		stores.values.forEach(ViewModelStore::clear)
		stores.clear()
	}

	private class Owner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner

	private companion object {
		const val SAVED = "request"
	}
}

/** The height the bar takes at the foot of a page: the thumbnail and the text beside it. */
internal val MiniBarHeight = 56.dp

/** How much of its foot a page leaves free for the bar: the bar's height while something is docked, else nothing. */
internal val LocalDockRoom = compositionLocalOf { 0.dp }

/**
 * Where the tabs' pages end, in window pixels, with the phone's tab bar fully up, so the bar sits right under them
 * (above the tab bar on a phone, beside the sidebar on a tablet). Null while the tabs are not composed.
 */
@Stable
internal class DockAnchor {
	var pages: Rect? by mutableStateOf(null)

	/** How far the phone's tab bar has slid away on scroll, in pixels: the bar rides with it. Read in layout only. */
	var hidden: () -> Float = { 0f }
}

internal val LocalDockAnchor = staticCompositionLocalOf { DockAnchor() }

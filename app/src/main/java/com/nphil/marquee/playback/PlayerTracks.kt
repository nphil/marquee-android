package com.nphil.marquee.playback

import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.nphil.marquee.core.DELIVERY_IN_STREAM
import com.nphil.marquee.core.DELIVERY_NONE
import com.nphil.marquee.core.DELIVERY_SIDECAR
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlayTracks
import com.nphil.marquee.core.SubtitleOption
import com.nphil.marquee.core.SubtitleSource
import com.nphil.marquee.data.SubtitleReveal
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * The open stream's audio and subtitle tracks as the menus show them, and the subtitle on screen. Core names the
 * tracks and says how each one shows ([PlayTracks]); this carries that out on the player: a file's audio and a
 * subtitle inside the file are selected on it, and a separate text file is downloaded and followed by the playback
 * clock. Main thread.
 */
@OptIn(UnstableApi::class)
@Stable
internal class PlayerTracks(
	private val scope: CoroutineScope,
	private val http: OkHttpClient,
	/** Where the title is, in milliseconds. */
	private val movieMs: () -> Long,
	core: MarqueeCore,
	private val speechDock: Flow<String?>,
) {
	/** What the menus list for the open stream. */
	var lists by mutableStateOf(PlayTracks())
		private set

	/** The audio the open stream plays; null when not known. */
	var audioId: Int? by mutableStateOf(null)
		private set

	/** The subtitle on screen; null for none. */
	var subtitleId: Int? by mutableStateOf(null)
		private set

	/** What the subtitle draws now. */
	var cues: List<Cue> by mutableStateOf(emptyList(), referentialEqualityPolicy())
		private set

	/** How the subtitle on screen is delivered: [DELIVERY_SIDECAR], [DELIVERY_IN_STREAM], or none. */
	private var how = DELIVERY_NONE

	/** The stream is the file itself, so its audio tracks are the player's to select; the server's conversion plays one. */
	private var isFile = false
	private var moving = false
	private var sidecar: Job? = null
	private var embedded: List<CaptionFrame> = emptyList()
	private var streamDraw: Job? = null
	private var loaded: CompletableDeferred<Boolean>? = null
	private var subtitleSource: SubtitleSource? = null
	private val reveal = MutableStateFlow(SubtitleReveal.Off)
	private val timing = PlayerWordTiming(core, scope)
	private val renderer = CaptionReveal(core, timing)

	fun setReveal(mode: SubtitleReveal) {
		if (reveal.value == mode) return
		reveal.value = mode
		if (how == DELIVERY_IN_STREAM) drawEmbedded()
	}

	/** Plex may list a new separate file since the menu was opened. */
	fun refresh(tracks: PlayTracks) { lists = tracks }

	suspend fun awaitSidecar(id: Int): Boolean = subtitleId == id && loaded?.await() == true && subtitleId == id

	fun motion(playing: Boolean) {
		moving = playing
		if (playing && how == DELIVERY_IN_STREAM && reveal.value != SubtitleReveal.Off) drawEmbedded()
	}

	fun reposition() {
		timing.reposition()
		if (how == DELIVERY_IN_STREAM) drawEmbedded()
	}

	/** Takes the tracks of a stream that is opening, and shows the subtitle its route carries. */
	fun begin(plan: PlayPlan, isFile: Boolean, online: SubtitleSource?) {
		stop()
		this.isFile = isFile
		subtitleSource = online
		lists = plan.tracks
		audioId = plan.route.audio
		val route = plan.route.subtitle
		// A picture subtitle the route cannot draw is chosen but not shown: the menus read as off.
		showOption(lists.subtitles.firstOrNull { it.id == route.track }, route.kind)
	}

	/** Puts [option] on screen, or nothing for null, without opening the stream again. [engine] takes the track selection that goes with it. */
	fun show(option: SubtitleOption?, engine: Player?) {
		stop()
		showOption(option, option?.delivery)
		engine?.let(::sync)
	}

	private fun showOption(option: SubtitleOption?, delivery: String?) {
		how = delivery?.takeIf { option != null && (it == DELIVERY_SIDECAR || it == DELIVERY_IN_STREAM) } ?: DELIVERY_NONE
		subtitleId = option?.id.takeIf { how != DELIVERY_NONE }
		if (how == DELIVERY_SIDECAR && option != null) option.url?.let { load(option.id, it) }
	}

	/** The cues of the subtitle selected inside the file or the server's stream; the others draw their own. */
	fun onCues(shown: List<Cue>, startMs: Long = movieMs()) {
		if (how != DELIVERY_IN_STREAM) return
		embedded = shown.map { CaptionFrame(it, startMs) }
		drawEmbedded()
	}

	private fun drawEmbedded() {
		streamDraw?.cancel()
		if (reveal.value == SubtitleReveal.Off || embedded.isEmpty()) {
			cues = embedded.map { it.cue }
			return
		}
		streamDraw = scope.launch {
			do {
				cues = renderer.draw(embedded, movieMs(), reveal.value)
				if (renderer.animating && moving) delay(REVEAL_MS)
			} while (renderer.animating && moving)
		}
	}

	/** Makes the player's own selection the one the menus show. Safe to call again: it changes nothing that is already right. */
	fun sync(engine: Player) {
		val groups = engine.currentTracks.groups
		val edit = engine.trackSelectionParameters.buildUpon()
		var changed = false

		if (isFile && lists.audio.size > 1) {
			val wanted = lists.audio.indexOfFirst { it.id == audioId }
			val group = pick(lists.audio.map { it.language }, wanted, groups.filter { it.type == C.TRACK_TYPE_AUDIO })
			if (group != null && group.isTrackSupported(0) && !group.isSelected) {
				edit.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
				changed = true
			}
		}

		// The player lists the file's own subtitles in file order, as Core does; the separate files are not among them.
		val inside = lists.subtitles.filter { !it.isExternal }
		val shown = if (how == DELIVERY_IN_STREAM) inside.indexOfFirst { it.id == subtitleId } else -1
		val text = pick(inside.map { it.language }, shown, groups.filter { it.type == C.TRACK_TYPE_TEXT })
		val off = C.TRACK_TYPE_TEXT in engine.trackSelectionParameters.disabledTrackTypes
		if (text != null && text.isTrackSupported(0)) {
			if (off || !text.isSelected) {
				edit.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(text.mediaTrackGroup, 0))
				changed = true
			}
		} else if (!off) {
			edit.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
			changed = true
		}

		if (changed) engine.trackSelectionParameters = edit.build()
	}

	/** Ends what draws a subtitle: the stream is closing or another is chosen. */
	fun stop() {
		sidecar?.cancel()
		sidecar = null
		streamDraw?.cancel()
		streamDraw = null
		loaded?.complete(false)
		loaded = null
		embedded = emptyList()
		timing.stop()
		renderer.reset()
		how = DELIVERY_NONE
		cues = emptyList()
	}

	/** The group of the option at [index] among [options]' languages, from the player's [groups] of that kind. */
	private fun pick(options: List<String?>, index: Int, groups: List<Tracks.Group>): Tracks.Group? {
		if (index < 0) return null
		val at = matchTrack(options, index, groups.map { it.mediaTrackGroup.getFormat(0).language }) ?: return null
		return groups.getOrNull(at)
	}

	/** Downloads one separate file, then uses its bytes both for drawing and (when enabled) for alignment. */
	private fun load(id: Int, url: String) {
		val result = CompletableDeferred<Boolean>()
		loaded = result
		val headers = lists.headers
		sidecar = scope.launch {
			try {
				val bytes = download(url, headers) ?: return@launch
				val file = withContext(Dispatchers.Default) {
					try {
						Captions.parse(bytes)
					} catch (_: IllegalArgumentException) {
						null
					}
				} ?: return@launch
				if (file.isEmpty) return@launch
				result.complete(true)
				subtitleSource?.let { source ->
					launch {
						combine(reveal, speechDock.catch { emit(null) }) { mode, address -> address?.takeIf { mode != SubtitleReveal.Off } }
							.distinctUntilChanged().collect { origin ->
								if (origin == null) timing.stop() else timing.prepare(source, id, origin, bytes)
							}
					}
				}
				while (true) {
					val due = movieMs()
					val mode = reveal.value
					if (mode != SubtitleReveal.Off) timing.ask(due)
					cues = renderer.draw(file.framesAt(due), due, mode)
					delay(if (renderer.animating && moving) REVEAL_MS else FOLLOW_MS)
				}
			} finally {
				result.complete(false)
			}
		}
	}

	/** The URL can carry a Plex token and must not appear in logs. */
	private suspend fun download(url: String, headers: Map<String, String>): ByteArray? = suspendCancellableCoroutine { continuation ->
		val call = try {
			val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
			http.newCall(request)
		} catch (_: IllegalArgumentException) {
			continuation.resume(null)
			return@suspendCancellableCoroutine
		}
		continuation.invokeOnCancellation { call.cancel() }
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				if (!call.isCanceled()) Diag.warning(DiagCategory.Player, "subtitle.load.failed", "error" to e.javaClass.simpleName)
				continuation.resume(null)
			}

			override fun onResponse(call: Call, response: Response) {
				val bytes = try {
					response.use {
						if (it.isSuccessful) it.body?.bytes() else {
							Diag.warning(DiagCategory.Player, "subtitle.load.failed", "status" to it.code)
							null
						}
					}
				} catch (_: IOException) {
					null
				}
				continuation.resume(bytes)
			}
		})
	}

	private companion object {
		const val FOLLOW_MS = 100L
		const val REVEAL_MS = 33L
	}
}

/**
 * Which of the player's tracks is the one Core lists at [index] among [options] (their languages): by position when
 * the player lists as many as Core does, else the same place among the tracks of that language. Null when the
 * language is unknown or the player has none of it.
 */
internal fun matchTrack(options: List<String?>, index: Int, groups: List<String?>): Int? {
	if (options.size == groups.size) return index
	val wanted = options.getOrNull(index) ?: return null
	val rank = options.subList(0, index).count { sameLanguage(wanted, it) }
	val same = groups.indices.filter { sameLanguage(wanted, groups[it]) }
	return same.getOrNull(rank) ?: same.firstOrNull()
}

/** The two languages are one, by the part before any region ("en" and "en-US"); an unknown language is never one. */
internal fun sameLanguage(a: String?, b: String?): Boolean {
	val first = a?.substringBefore('-')?.lowercase()
	return !first.isNullOrEmpty() && first != "und" && first == b?.substringBefore('-')?.lowercase()
}

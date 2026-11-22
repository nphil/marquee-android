package com.nphil.marquee.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nphil.marquee.core.MediaUrl
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The theme tune of the show page on screen. One player serves the whole app, so a season pushed over its show
 * carries the same tune on; pages only say which tune they want (the iPhone app's ThemeMusicPlayer).
 *
 * A paused tune resumes when its page shows again, starts over once a full-screen player has been open, and does
 * not repeat after playing to its end. It mixes with other apps' audio, so it holds no audio focus: it waits for
 * their sound before it starts ([ThemeMusic.plays]) and fades out when it begins. Everything runs on the main
 * thread, on [scope].
 */
class ThemeMusicPlayer internal constructor(
	private val scope: CoroutineScope,
	private val factory: PlaybackFactory,
	private val coordinator: PlaybackCoordinator,
	/** Where the file at a path is and how to ask for it; null when the server cannot be reached. */
	private val source: suspend (path: String) -> MediaUrl?,
) {
	/** The tune of one show on one server. */
	data class Identity(val server: String?, val key: String)

	class Want(val key: String, val server: String?) {
		val identity = Identity(server, key)
	}

	/** The volume setting, 0 to 1. */
	var level = ThemeFade.DEFAULT_LEVEL
		set(value) {
			field = value
			player?.volume = fade.volume(value.toDouble())
		}

	/** The pages asking for a tune, oldest first; the last one wins. */
	private val wants = mutableListOf<Pair<Any, Want>>()
	private var loadedIdentity: Identity? = null
	private var player: ExoPlayer? = null
	private var loading: Job? = null
	private var finished = false

	/** A full-screen player opened: the tune starts over rather than resuming. */
	private var forgets = false
	private var fade = ThemeFade()
	private var fading: Job? = null
	private var hold: PlaybackCoordinator.Hold? = null

	/** The tune is playing and can be heard; a trailer that starts meanwhile plays silently. */
	private var isHeard = false
		set(value) {
			if (field == value) return
			field = value
			coordinator.themeHeard = value
			// The player's own sound is not another app's: the signals subtract it.
			if (value) {
				hold = coordinator.holdAudio()
			} else {
				hold?.release()
				hold = null
			}
		}

	/** What [page] wants now: its show's tune, or nothing once it leaves the screen or something else holds the sound. */
	fun update(page: Any, want: Want?) {
		wants.removeAll { it.first === page }
		if (want != null) wants += page to want
		reconcile()
	}

	fun forget() {
		if (loadedIdentity == null) return
		forgets = true
		reconcile()
	}

	private val wantsLoaded: Boolean
		get() = wants.lastOrNull()?.second?.identity == loadedIdentity && loadedIdentity != null && !finished && !forgets

	private fun reconcile() {
		if (!wantsLoaded) {
			// Silence first; `settled` then loads the tune wanted, if any.
			fade.rising = false
			runFade()
			return
		}
		val player = player ?: return
		if (player.isPlaying) started() else player.play()
	}

	/** Sound has begun, so the fade in starts now rather than while the tune buffers. */
	private fun started() {
		if (!wantsLoaded || player?.isPlaying != true) return
		fade.rising = true
		isHeard = true
		runFade()
	}

	private fun runFade() {
		if (fading != null) return
		fading = scope.launch {
			var last = System.nanoTime()
			while (true) {
				if (fade.isSettled) {
					fading = null
					settled()
					return@launch
				}
				delay(FADE_STEP_MILLIS)
				val now = System.nanoTime()
				fade.advance((now - last) / NANOS_PER_SECOND)
				last = now
				player?.volume = fade.volume(level.toDouble())
			}
		}
	}

	private fun settled() {
		if (fade.rising) return
		player?.pause()
		isHeard = false
		val want = wants.lastOrNull()?.second
		if (want == null) {
			if (forgets) unload()
			return
		}
		if (want.identity != loadedIdentity || forgets) load(want)
	}

	private fun load(want: Want) {
		unload()
		loadedIdentity = want.identity
		loading = scope.launch {
			val found = source(want.key) ?: return@launch
			val next = factory.create(found.headers, PlaybackFactory.Kind.ThemeMusic)
			next.volume = 0f
			next.addListener(object : Player.Listener {
				override fun onIsPlayingChanged(isPlaying: Boolean) {
					if (player !== next) return
					if (isPlaying) started() else isHeard = false
				}

				override fun onPlaybackStateChanged(state: Int) {
					if (player === next && state == Player.STATE_ENDED) ended()
				}

				override fun onPlayerError(error: PlaybackException) {
					if (player !== next) return
					Diag.warning(DiagCategory.Audio, "theme.failed", "code" to error.errorCodeName)
					ended()
				}
			})
			next.setMediaItem(MediaItem.fromUri(found.url))
			next.prepare()
			player = next
			Diag.info(DiagCategory.Audio, "theme.load")
			reconcile()
		}
	}

	private fun ended() {
		finished = true
		isHeard = false
		fading?.cancel()
		fading = null
		fade = ThemeFade()
		player?.volume = 0f
	}

	private fun unload() {
		loading?.cancel()
		loading = null
		player?.release()
		player = null
		loadedIdentity = null
		finished = false
		forgets = false
		fading?.cancel()
		fading = null
		fade = ThemeFade()
		isHeard = false
	}

	private companion object {
		const val FADE_STEP_MILLIS = 30L
		const val NANOS_PER_SECOND = 1e9
	}
}

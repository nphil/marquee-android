package com.nphil.marquee.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the app's players tell each other: whether a trailer is sounding or the theme tune is heard, whether a
 * full-screen player is open, and how many of the app's own players are making sound (so [PlaybackSignals] can tell
 * another app's audio from the app's own). One lives on the container. Everything here is read and written on the
 * main thread, as snapshot state.
 */
@Stable
class PlaybackCoordinator {
	/** The page's trailer is playing with sound; the theme tune waits for it. */
	var trailerSounding by mutableStateOf(false)
		internal set

	/** The theme tune is playing and can be heard; a trailer that starts meanwhile plays silently. */
	var themeHeard by mutableStateOf(false)
		internal set

	private var openPlayers by mutableIntStateOf(0)
	private var soundingPlayers by mutableIntStateOf(0)

	/** A full-screen player (the extras viewer, later the main player) is open and holds the audio. */
	val playerOpen: Boolean get() = openPlayers > 0

	/** How many of the app's players have sound going out now. */
	val ownAudio: Int get() = soundingPlayers

	/** Marks a full-screen player open until the returned hold is released. */
	fun holdPlayerOpen(): Hold {
		openPlayers++
		return Hold { openPlayers-- }
	}

	/** Counts one of the app's players as making sound until the returned hold is released. */
	fun holdAudio(): Hold {
		soundingPlayers++
		return Hold { soundingPlayers-- }
	}

	/** The app-wide theme music player, made on first use because it needs the app's client and session. */
	internal var themeMusic: ThemeMusicPlayer? = null

	/** A claim that releasing twice does not undo twice. */
	class Hold internal constructor(private val onRelease: () -> Unit) {
		private var held = true

		fun release() {
			if (!held) return
			held = false
			onRelease()
		}
	}
}

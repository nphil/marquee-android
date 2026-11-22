package com.nphil.marquee.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.nphil.marquee.MainActivity
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory

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

	/** The media session of the title that plays, which the notification, the lock screen and headset buttons show. Null when none does. */
	internal var session: MediaSession? = null
		private set

	/** The service that shows [session]; it sets itself while it runs. */
	internal var service: PlaybackService? = null

	/**
	 * Puts [engine] on a media session and makes sure the service that keeps the app playing with the screen off is
	 * running. One title holds the session at a time.
	 */
	internal fun publish(context: Context, engine: Player) {
		retract(context, stopService = false)
		val open = PendingIntent.getActivity(
			context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val made = MediaSession.Builder(context, engine).setSessionActivity(open).build()
		session = made
		val running = service
		if (running != null) {
			running.addSession(made)
			return
		}
		try {
			context.startService(Intent(context, PlaybackService::class.java))
		} catch (e: IllegalStateException) {
			// Android refuses a service start from the background; the title still plays, without the notification.
			Diag.warning(DiagCategory.Player, "session.service.refused", "error" to e.javaClass.simpleName)
		}
	}

	/** Takes the session down before its player goes; [stopService] also ends the service, or leaves it for the next title. */
	internal fun retract(context: Context, stopService: Boolean = true) {
		session?.let { old ->
			session = null
			service?.removeSession(old)
			old.release()
		}
		if (stopService) {
			service = null
			context.stopService(Intent(context, PlaybackService::class.java))
		}
	}

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

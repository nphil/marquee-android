package com.nphil.marquee.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nphil.marquee.container

/**
 * Keeps a title playing while the app is behind another or the screen is off, and lets the system's notification, lock
 * screen and headset buttons control it. The player screen owns the session ([PlaybackCoordinator.publish]); this only
 * shows it.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
	override fun onCreate() {
		super.onCreate()
		val playback = container.playback
		playback.service = this
		playback.session?.let(::addSession)
	}

	/** Only the system's own controllers may connect: the session is not offered to other apps. */
	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
		if (controllerInfo.isTrusted) container.playback.session else null

	/** Swiping the app out of Recents ends the playback. */
	override fun onTaskRemoved(rootIntent: Intent?) {
		pauseAllPlayersAndStopSelf()
	}

	override fun onDestroy() {
		val playback = container.playback
		if (playback.service === this) playback.service = null
		super.onDestroy()
	}
}

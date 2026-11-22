package com.nphil.marquee.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Transient audio focus for a trailer that has sound, held only while it has. Another app taking the focus, or a
 * request the system delays, leaves [held] false; the trailer then plays silently, as it does over other audio,
 * and has the sound back when the focus returns. All calls are on the main thread.
 */
internal class TrailerFocus(context: Context) {
	private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
	private var request: AudioFocusRequest? = null

	/** The trailer may make sound: the app holds the focus now. */
	var held by mutableStateOf(false)
		private set

	fun request() {
		if (request != null) return
		val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
			.setAudioAttributes(
				AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
			)
			.setAcceptsDelayedFocusGain(true)
			.setOnAudioFocusChangeListener { change ->
				held = change == AudioManager.AUDIOFOCUS_GAIN || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
			}
			.build()
		request = next
		held = audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
	}

	fun abandon() {
		val last = request ?: return
		request = null
		held = false
		audio.abandonAudioFocusRequest(last)
	}
}

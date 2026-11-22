package com.nphil.marquee.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * Builds the app's Media3 players. Files come over the app's own OkHttp client with the headers Core supplies as
 * default request properties, so no token is in a URL and the request log still sees every request.
 */
@OptIn(UnstableApi::class)
class PlaybackFactory(private val context: Context, private val http: OkHttpClient) {
	enum class Kind {
		/** The hero preview: no subtitles, audio focus handled by the caller, which mutes it at times. */
		Trailer,

		/** A show's theme tune: mixes with other apps, which it waits for rather than asks to pause. */
		ThemeMusic,

		/** The extras viewer: holds audio focus while it plays and pauses when headphones come out. */
		Viewer,
	}

	/** A player for [kind] that fetches with [headers]; the caller sets the media item and releases it. Main thread. */
	fun create(headers: Map<String, String>, kind: Kind): ExoPlayer {
		val source = DefaultMediaSourceFactory(OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers))
		val player = ExoPlayer.Builder(context).setMediaSourceFactory(source).build()
		val content = if (kind == Kind.ThemeMusic) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE
		val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(content).build()
		player.setAudioAttributes(attributes, kind == Kind.Viewer)
		player.repeatMode = Player.REPEAT_MODE_OFF
		when (kind) {
			Kind.Trailer -> player.trackSelectionParameters =
				player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
			Kind.Viewer -> player.setHandleAudioBecomingNoisy(true)
			Kind.ThemeMusic -> Unit
		}
		return player
	}
}

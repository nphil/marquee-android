package com.nphil.marquee.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nphil.marquee.ffmpeg.FfmpegVideoRenderer
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

		/** The title player: holds audio focus and pauses when headphones come out. Text tracks stay off until the track menus. */
		Player,
	}

	/** A player for [kind] that fetches with [headers]; the caller sets the media item and releases it. Main thread. */
	fun create(headers: Map<String, String>, kind: Kind): ExoPlayer {
		val source = DefaultMediaSourceFactory(data(headers))
		// ON, not PREFER: the FFmpeg audio renderer (:ffmpeg) then sits behind the platform's, so it plays only what the
		// platform's decoders and any passthrough to a receiver cannot. FFmpeg's MPEG-2 video renderer is behind
		// MediaCodec's too, and claims MPEG-2 only on a tablet whose codec list has no decoder for it.
		val renderers = SoftVideoRenderers(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
		val builder = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(source)
		if (kind == Kind.Player) builder.setLoadControl(titleLoadControl())
		val player = builder.build()
		val content = if (kind == Kind.ThemeMusic) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE
		val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(content).build()
		player.setAudioAttributes(attributes, kind == Kind.Viewer || kind == Kind.Player)
		player.repeatMode = Player.REPEAT_MODE_OFF
		when (kind) {
			Kind.Trailer -> player.trackSelectionParameters =
				player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
			Kind.Player -> {
				player.trackSelectionParameters =
					player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
				player.setHandleAudioBecomingNoisy(true)
			}
			Kind.Viewer -> player.setHandleAudioBecomingNoisy(true)
			Kind.ThemeMusic -> Unit
		}
		return player
	}

	/** What [url] opens: the server's HLS conversion when [hls], else the file itself, fetched with [headers]. [about] is what the system's controls show for it. */
	fun source(headers: Map<String, String>, url: String, hls: Boolean, about: MediaMetadata = MediaMetadata.EMPTY): MediaSource {
		val item = MediaItem.Builder().setUri(url).setMediaMetadata(about).build()
		val factory = if (item.localConfiguration?.uri?.scheme == "file") DefaultDataSource.Factory(context) else data(headers)
		return if (hls) HlsMediaSource.Factory(factory).createMediaSource(item) else ProgressiveMediaSource.Factory(factory).createMediaSource(item)
	}

	private fun data(headers: Map<String, String>) = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
}

/**
 * Keeps each track's last keyframe behind the playhead. Playing in the background switches the video track off, and
 * without a back buffer the player drops that keyframe while the track is off, so switching it back on reloaded the
 * file from the playhead: a 0.5-1.3 s stall and a new range request. With the keyframe kept the track resumes from memory.
 */
@OptIn(UnstableApi::class)
internal fun titleLoadControl(): LoadControl = DefaultLoadControl.Builder().setBackBuffer(0, true).build()

/** The default renderers, and FFmpeg's MPEG-2 video renderer after MediaCodec's for the tablets that have no MPEG-2 decoder. */
@OptIn(UnstableApi::class)
private class SoftVideoRenderers(context: Context) : DefaultRenderersFactory(context) {
	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: ArrayList<Renderer>,
	) {
		super.buildVideoRenderers(
			context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
			allowedVideoJoiningTimeMs, out,
		)
		out.add(
			FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
				DeviceDecoders.installed.platformLacks(MimeTypes.VIDEO_MPEG2)
			},
		)
	}
}

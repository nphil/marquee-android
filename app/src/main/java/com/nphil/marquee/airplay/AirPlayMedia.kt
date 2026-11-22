package com.nphil.marquee.airplay

import com.nphil.marquee.core.PlayStream
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import java.net.URI
import java.net.URISyntaxException

internal fun acceptsAirPlayVideo(receiver: Receiver): Boolean = receiver.isSupported && receiver.properties["acl"] != "1"

internal fun validAirPlaySecret(value: String, password: Boolean): Boolean =
	if (password) value.isNotEmpty() else value.length == 4 && value.all { it in '0'..'9' }

/** The bridge mints the only credential this path accepts: a server's temporary delegation token. */
internal fun airPlayFileUrl(stream: PlayStream?, contentType: String): String? {
	if (stream == null || stream.kind != PlayStream.StreamKind.File || stream.headers.isNotEmpty() || contentType != "video/mp4") return null
	val uri = try {
		URI(stream.url)
	} catch (_: URISyntaxException) {
		return null
	}
	if ((uri.scheme != "http" && uri.scheme != "https") || uri.host == null || uri.rawUserInfo != null || uri.rawFragment != null ||
		uri.rawPath?.startsWith("/library/parts/") != true) return null
	val query = uri.rawQuery ?: return null
	if (!query.startsWith("X-Plex-Token=") || query.length <= "X-Plex-Token=".length || '&' in query || ';' in query) return null
	return stream.url
}

internal fun airPlayState(state: PlaybackState, rate: Double, ready: Boolean): String? = when (state) {
	PlaybackState.Playing -> if (rate > 0) "playing" else "buffering"
	PlaybackState.Paused -> "paused"
	PlaybackState.Loading -> "buffering"
	PlaybackState.Stopped -> if (ready) "cancelled" else null
	PlaybackState.Unknown -> null
}

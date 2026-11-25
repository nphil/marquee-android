package com.nphil.marquee.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.container
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.MediaUrl
import com.nphil.marquee.core.mediaUrl
import com.nphil.marquee.data.PlaybackPrefs
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Where the server's file at [path] is and the headers that carry the token, or null when the server cannot be
 * reached. The address holds no token, so it is safe to log and to hand to a player.
 */
internal suspend fun AppContainer.playbackSource(path: String): MediaUrl? = try {
	session.onServer { server, remembered -> core.mediaUrl(server, remembered, path) }
} catch (e: CoreException) {
	Diag.warning(DiagCategory.Player, "source.failed", "kind" to e.kind.name)
	null
}

/** The active profile's playback settings, following a change of profile. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun AppContainer.playbackPrefsFlow(): Flow<PlaybackPrefs> =
	session.state.map { it.user?.uuid }.distinctUntilChanged().flatMapLatest { settings.playback(it) }

/** The active profile's trailer and theme music choices; null until they are read, so nothing plays on a guess. */
@Composable
fun rememberPlaybackPrefs(): State<PlaybackPrefs?> {
	val container = LocalContext.current.container
	val prefs = remember(container) { container.playbackPrefsFlow() }
	return prefs.collectAsStateWithLifecycle(null)
}

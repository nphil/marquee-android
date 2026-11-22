package com.nphil.marquee.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nphil.marquee.container
import com.nphil.marquee.core.PlexItem

/**
 * Plays the theme tune of a show's page, or of a season's page, while the page is on screen. [item] is the page's
 * show or season; [covered] is true while another page or the viewer is above this one. Does nothing when the item has
 * no tune or the profile turned theme music off.
 *
 * The tune waits while other audio plays or the page's trailer is heard, fades in over 1.5 s and out over 1 s, and
 * does not repeat once it has played to its end. Covered, it pauses and resumes; after a full-screen player it starts
 * over ([ThemeMusicPlayer]).
 */
@Composable
fun ThemeMusicEffect(item: PlexItem?, covered: Boolean) {
	val context = LocalContext.current
	val container = context.container
	val coordinator = container.playback
	val prefs by rememberPlaybackPrefs()
	val signals = rememberPlaybackSignals()
	val shown by rememberUpdatedState(!covered)
	val key = remember(item?.type, item?.theme, item?.parentTheme) { item?.let(ThemeMusic::key) }
	val wanted by remember(key) {
		derivedStateOf {
			key != null && ThemeMusic.plays(
				ThemeMusic.Conditions(
					enabled = prefs?.playsThemeMusic == true,
					appActive = signals.appActive,
					pageVisible = shown,
					playerOpen = coordinator.playerOpen,
					otherAudioPlaying = signals.otherAudio,
					trailerSounding = coordinator.trailerSounding,
				),
			)
		}
	}
	val player = remember(container) {
		coordinator.themeMusic ?: ThemeMusicPlayer(container.scope, PlaybackFactory(context, container.http), coordinator, container::playbackSource)
			.also { coordinator.themeMusic = it }
	}
	// One identity per page, so a season over its show is a second page asking for the same tune.
	val page = remember { Any() }

	LaunchedEffect(player, wanted, key) {
		val want = key?.takeIf { wanted }?.let { ThemeMusicPlayer.Want(it, container.session.state.value.server?.id) }
		player.update(page, want)
	}
	LaunchedEffect(player, prefs?.themeMusicVolume) { prefs?.let { player.level = it.themeMusicVolume } }
	LaunchedEffect(player, coordinator.playerOpen) { if (coordinator.playerOpen) player.forget() }
	DisposableEffect(player, page) { onDispose { player.update(page, null) } }
}

package com.nphil.marquee.ui.player

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nphil.marquee.playback.PlayerModel

/**
 * What the title does when the app leaves the screen. A stop while the window is the small one means the person
 * closed it, so the title pauses. Otherwise the model decides whether the sound carries on with the picture off;
 * it gets the picture back when the app returns. Losing focus alone (a split screen, a dialog) never pauses.
 */
@Composable
fun BackgroundPlaybackEffect(model: PlayerModel, pictureInPicture: PictureInPicture) {
	val activity = LocalActivity.current
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
		if (pictureInPicture.active || activity?.isInPictureInPictureMode == true) model.pause() else model.background(true)
	}
	LifecycleEventEffect(Lifecycle.Event.ON_START) { model.background(false) }
}

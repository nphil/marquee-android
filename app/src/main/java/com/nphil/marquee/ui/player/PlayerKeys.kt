package com.nphil.marquee.ui.player

import androidx.compose.ui.input.key.Key

/** What a key does to the player. */
internal enum class KeyAction(val repeats: Boolean = false) {
	Toggle,
	Play,
	Pause,
	Back,
	Forward,
	VolumeUp(repeats = true),
	VolumeDown(repeats = true),
	Aspect,
	Mute,
	Next,
}

/**
 * The player's shortcut for [key], or null when the key is not one. The arrows, Enter and the gamepad's A are shortcuts
 * only while the picture itself has the focus ([onPicture]); with a control focused they move between the controls and
 * press them, as they do everywhere else.
 */
internal fun keyAction(key: Key, onPicture: Boolean): KeyAction? = when (key) {
	Key.Spacebar, Key.K, Key.MediaPlayPause -> KeyAction.Toggle
	Key.MediaPlay -> KeyAction.Play
	Key.MediaPause -> KeyAction.Pause
	Key.J, Key.MediaRewind, Key.ButtonL1 -> KeyAction.Back
	Key.L, Key.MediaFastForward, Key.ButtonR1 -> KeyAction.Forward
	Key.F -> KeyAction.Aspect
	Key.M -> KeyAction.Mute
	Key.MediaNext -> KeyAction.Next
	Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA -> if (onPicture) KeyAction.Toggle else null
	Key.DirectionLeft -> if (onPicture) KeyAction.Back else null
	Key.DirectionRight -> if (onPicture) KeyAction.Forward else null
	Key.DirectionUp -> if (onPicture) KeyAction.VolumeUp else null
	Key.DirectionDown -> if (onPicture) KeyAction.VolumeDown else null
	else -> null
}

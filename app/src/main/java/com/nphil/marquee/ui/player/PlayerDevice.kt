package com.nphil.marquee.ui.player

import android.content.ContentResolver
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager

/**
 * The screen's brightness while the player is open. The level is the window's own, so the system setting is left as
 * it was and [restore] gives the window back to it.
 */
internal class Brightness(private val window: Window?, private val resolver: ContentResolver) {
	private val original = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

	/** What the screen shows now, 0 to 1: the window's level when the player set one, else the system's. */
	fun level(): Float {
		val own = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
		if (own >= 0f) return own
		return (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, SYSTEM_DEFAULT) / SYSTEM_MAX).coerceIn(0f, 1f)
	}

	fun set(level: Float) = put(level.coerceIn(BRIGHTNESS_FLOOR, 1f))

	fun restore() = put(original)

	private var held: Float? = null

	/** Gives the window back to the system's level while the player is not full screen, keeping the player's own. */
	fun release() {
		if (held != null) return
		held = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
		restore()
	}

	/** Takes the player's own level up again after [release]. */
	fun reclaim() {
		val level = held ?: return
		held = null
		put(level)
	}

	private fun put(value: Float) {
		val target = window ?: return
		target.attributes = target.attributes.also { it.screenBrightness = value }
	}

	private companion object {
		const val SYSTEM_MAX = 255f
		const val SYSTEM_DEFAULT = 128
	}
}

/** The media volume, in the device's own steps. */
internal class Volume(private val audio: AudioManager) {
	private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

	/** 0 to 1. */
	fun level(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()

	fun set(level: Float) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeStep(level, max), 0)

	/** One step up or down, answering the level it reached. */
	fun step(up: Boolean): Float {
		audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
		return level()
	}
}

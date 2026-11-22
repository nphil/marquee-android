package com.nphil.marquee.playback

import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem

/**
 * Theme music on a show's pages: the show's theme tune plays while its page is on screen and nothing else holds
 * the sound. Mirrors MarqueeCore's `ThemeMusic` (core/Packages/MarqueeCore/Sources/MarqueeCore/Playback/ThemeMusic.swift);
 * a change to the rule lands there first.
 */
object ThemeMusic {
	/** The tune a title's page plays: a show's own, and on a season's page its show's. */
	fun key(item: PlexItem): String? {
		val key = when (item.type) {
			MediaType.Show -> item.theme
			MediaType.Season -> item.parentTheme
			else -> null
		}
		return key?.takeIf { it.isNotEmpty() }
	}

	/** What the tablet is doing at this moment. */
	data class Conditions(
		/** The profile's "Play Theme Music" setting. */
		val enabled: Boolean = true,
		val appActive: Boolean = true,
		/** The page is shown, not covered by another page. */
		val pageVisible: Boolean = true,
		/** A full-screen player holds the audio. */
		val playerOpen: Boolean = false,
		/** Music, a podcast or a call from another app is playing. */
		val otherAudioPlaying: Boolean = false,
		/** The page's trailer is playing with sound. */
		val trailerSounding: Boolean = false,
	)

	/**
	 * Unlike a trailer, which plays muted over other audio, the tune waits for it to stop. A trailer that started
	 * first keeps the sound; one that starts while the tune plays is muted ([TrailerAutoplay]).
	 */
	fun plays(c: Conditions): Boolean =
		c.enabled && c.appActive && c.pageVisible && !c.playerOpen && !c.otherAudioPlaying && !c.trailerSounding
}

/**
 * How loud the theme tune is over time. Its gain moves at a steady pace towards full over [FADE_IN_SECONDS] or
 * towards silence over [FADE_OUT_SECONDS], from wherever it is, so a fade reversed midway carries on without a
 * jump. The volume is the setting times the gain squared, which is heard as an even fade.
 */
class ThemeFade {
	/** 0 is silent, 1 full. */
	var gain = 0.0
		private set

	/** Heading for full; otherwise for silence. */
	var rising = false

	val isSettled: Boolean get() = gain == if (rising) 1.0 else 0.0

	fun advance(seconds: Double) {
		if (seconds <= 0) return
		gain = if (rising) minOf(1.0, gain + seconds / FADE_IN_SECONDS) else maxOf(0.0, gain - seconds / FADE_OUT_SECONDS)
	}

	/** The player's volume at [level], the volume setting from 0 to 1. */
	fun volume(level: Double): Float = (level.coerceIn(0.0, 1.0) * gain * gain).toFloat()

	companion object {
		const val FADE_IN_SECONDS = 1.5
		const val FADE_OUT_SECONDS = 1.0

		/** The volume setting a profile starts with. */
		const val DEFAULT_LEVEL = 0.5f
	}
}

package com.nphil.marquee.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The hero trailer on a title's page: it starts after a short dwell, with sound, when the setting is on and
 * nothing about the tablet argues against it. Mirrors MarqueeCore's `TrailerAutoplay`
 * (core/Packages/MarqueeCore/Sources/MarqueeCore/Playback/TrailerAutoplay.swift); a change to the rule lands there first.
 */
object TrailerAutoplay {
	/** How long a page stays in view before its trailer starts. */
	val dwell: Duration = 2.seconds

	/** What the tablet is doing at this moment. */
	data class Conditions(
		/** The profile's "Play Trailers Automatically" setting. */
		val enabled: Boolean = true,
		/** Battery Saver is on. */
		val powerSaver: Boolean = false,
		/** The system's animations are turned off. */
		val reduceMotion: Boolean = false,
		/** The connection is metered: mobile data or a hotspot. */
		val metered: Boolean = false,
		val appActive: Boolean = true,
		/** The hero is on screen: the page is on top and not scrolled past it. */
		val heroVisible: Boolean = true,
		/** A full-screen player holds the audio. */
		val playerOpen: Boolean = false,
		/** Music, a podcast or a call from another app is playing, or the show's theme tune. */
		val otherAudioPlaying: Boolean = false,
	)

	sealed interface Verdict {
		/** Play it; muted when other audio is already playing, so the trailer never talks over it. */
		data class Play(val muted: Boolean) : Verdict

		data object Hold : Verdict
	}

	fun decide(c: Conditions): Verdict =
		if (c.enabled && !c.powerSaver && !c.reduceMotion && !c.metered && c.appActive && c.heroVisible && !c.playerOpen) {
			Verdict.Play(muted = c.otherAudioPlaying)
		} else {
			Verdict.Hold
		}
}

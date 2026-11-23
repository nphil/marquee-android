package com.nphil.marquee.link

import android.content.Context
import android.media.AudioManager
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.player.Volume
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The driving phone's side of a link: what it tells a controlling phone, and how it takes that phone's controls for
 * the player on its own screen. The player screen attaches its model while it is open; with none open the status
 * carries no title and the phone is not offered as something to control. Main thread only.
 *
 * Android has no big screen to navigate, so the eyes-free remote's input is not answered here, and no haptic cue
 * goes back.
 */
internal class LinkHost(context: Context) {
	private val volume = Volume(context.getSystemService(AudioManager::class.java))
	private var active: WeakReference<PlayerModel>? = null

	fun attach(model: PlayerModel) { active = WeakReference(model) }

	fun detach(model: PlayerModel) { if (active?.get() === model) active = null }

	fun status(): LinkStatus {
		val level = volume.level().toDouble()
		val model = active?.get() ?: return LinkStatus(volume = level)
		return LinkStatus(nowPlaying = playing(model), volume = level)
	}

	private fun playing(model: PlayerModel) = LinkNowPlaying(
		title = model.title,
		detail = model.subtitle,
		isPlaying = model.isPlaying,
		position = seconds(model.movieMs() / 1000.0),
		duration = seconds(model.durationMs / 1000.0),
		hasNext = model.next != null,
		subtitles = model.tracks.lists.menuSubtitles.map { LinkSubtitle(it.id, it.label) },
		subtitle = model.tracks.subtitleId,
	)

	/** JSON has no NaN or infinity; a player that does not know yet says 0. */
	private fun seconds(value: Double) = if (value.isFinite()) max(0.0, value) else 0.0

	/** A control from the controlling phone's remote: the player's, or the volume this phone plays at. */
	fun handle(wire: LinkWire) {
		when (wire.command) {
			"volume" -> wire.step?.let { volume.set(stepped(volume.level().toDouble(), it).toFloat()) }
			"setVolume" -> wire.level?.takeIf { it.isFinite() }?.let { volume.set(it.coerceIn(0.0, 1.0).toFloat()) }
			else -> active?.get()?.let { handle(wire, it) }
		}
	}

	private fun handle(wire: LinkWire, model: PlayerModel) {
		when (wire.command) {
			"playPause" -> model.toggle()
			"skip" -> wire.seconds?.takeIf { it.isFinite() }?.let { model.skip(it.roundToInt()) }
			"next" -> if (model.next != null) model.playNext()
			"subtitle" -> {
				val id = wire.track ?: return model.pickSubtitle(SubtitlePick.Off)
				if (model.tracks.lists.menuSubtitles.any { it.id == id }) model.pickSubtitle(SubtitlePick.track(id))
			}
		}
	}

	private companion object {
		/** Core's `LinkVolume`: the volume buttons' sixteen steps. A level between two steps first moves to the next one that way. */
		const val STEPS = 16

		fun stepped(level: Double, step: Int): Double {
			val clamped = level.coerceIn(0.0, 1.0)
			if (step == 0) return clamped
			val position = clamped * STEPS
			val nearest = position.roundToInt().toDouble()
			val base = if (abs(position - nearest) < 0.01) nearest else if (step > 0) floor(position) else ceil(position)
			return min(max(base + step, 0.0), STEPS.toDouble()) / STEPS
		}
	}
}

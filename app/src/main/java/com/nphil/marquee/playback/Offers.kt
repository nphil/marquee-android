package com.nphil.marquee.playback

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nphil.marquee.core.AtCredits
import com.nphil.marquee.core.AutoplayPlan
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.Marker
import com.nphil.marquee.core.PlayPlan
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.SkipVerdict
import com.nphil.marquee.core.playSkipBegin
import com.nphil.marquee.core.playSkipCheck
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the player offers over the picture. */
sealed interface Offer {
	/** The playhead is inside the intro, which ends [end] seconds into the title. */
	data class SkipIntro(val end: Double) : Offer

	/** The credits have begun and an episode follows. */
	data class UpNext(val next: PlexItem) : Offer
}

/** An offer taking itself: it started at [startedAt] on the elapsed-real-time clock and runs [lengthMs]. */
data class Countdown(val startedAt: Long, val lengthMs: Long)

/**
 * The offer the playhead at [seconds] calls for. Plex's markers are in milliseconds. Skip Intro shows inside an intro
 * marker until a second from its end, unless the intro was skipped already; Up Next shows from the credits marker
 * (the final one, else the last) when an episode follows and "Watch Credits" was not chosen.
 */
internal fun offerAt(
	seconds: Double,
	intro: List<Marker>,
	credits: List<Marker>,
	introSkipped: Boolean,
	creditsDismissed: Boolean,
	upcoming: PlexItem?,
): Offer? {
	val ms = seconds * 1000
	if (!introSkipped) {
		val marker = intro.firstOrNull { ms >= it.start && ms < it.end }
		if (marker != null && marker.end / 1000.0 - seconds > 1) return Offer.SkipIntro(marker.end / 1000.0)
	}
	val finale = credits.firstOrNull { it.isFinal } ?: credits.lastOrNull()
	if (!creditsDismissed && finale != null && ms >= finale.start && upcoming != null) return Offer.UpNext(upcoming)
	return null
}

/**
 * Skip Intro and Up Next for the title playing: when each shows, its countdown, and the confirmation that a skip landed.
 * Core says what the profile's rules do ([AutoplayPlan]) and judges the skip ([playSkipCheck]); this runs them on the
 * player. Main thread.
 */
@Stable
internal class PlayerOffers(
	private val scope: CoroutineScope,
	private val core: MarqueeCore,
	/** The title plays steadily: a stream is open, playing, and not waiting for data. */
	private val steady: () -> Boolean,
	/** A menu or a scrub is open, which holds an Up Next countdown back. */
	private val held: () -> Boolean,
	/** Moves to [seconds] into the title; false when nothing moved. */
	private val seek: (Double) -> Boolean,
	/** Plays the episode after this one. */
	private val advance: () -> Unit,
) {
	/** The offer on screen; null for none. */
	var offer: Offer? by mutableStateOf(null)
		private set

	/** The running countdown of [offer]; null while it waits for a tap. */
	var countdown: Countdown? by mutableStateOf(null)
		private set

	/** "Watch Credits" was chosen, or the credits already played on: the finished episode stays. */
	var creditsDismissed = false
		private set

	/** An Up Next countdown is running, so an episode that ends now goes on to the next. */
	val upNextCounting: Boolean get() = offer is Offer.UpNext && countdown != null

	private var session: String? = null
	private var intro: List<Marker> = emptyList()
	private var credits: List<Marker> = emptyList()
	private var introDelay = 0
	private var atCredits = AtCredits.Card
	private var creditsSeconds = 0
	private var upcoming: PlexItem? = null
	private var introCancelled = false
	private var skippedIntroEnd: Double? = null
	private var countdownStopped = false
	private var countdownJob: Job? = null

	// The skip being confirmed: where it went, whether Core has been told, and where playback was at the last look.
	private var checking: Double? = null
	private var checkBegun = false
	private var checkedAt: Double? = null

	/** The markers of the title that plays now, and how the profile wants its offers handled. */
	fun configure(plan: PlayPlan, introSkipDelay: Int, autoplay: AutoplayPlan) {
		session = plan.session
		intro = plan.intro
		credits = plan.credits
		introDelay = introSkipDelay
		atCredits = autoplay.atCredits
		creditsSeconds = autoplay.seconds ?: 0
	}

	/** The episode that follows, once the queue has named it. */
	fun follow(item: PlexItem?) {
		upcoming = item
	}

	/** A new title: nothing of the last one's offers carries over. */
	fun reset() {
		cancelCountdown("new-item")
		offer = null
		introCancelled = false
		skippedIntroEnd = null
		creditsDismissed = false
		countdownStopped = false
		checking = null
		checkedAt = null
		upcoming = null
	}

	/** The stream stopped for good. */
	fun stop() {
		cancelCountdown("stopped")
		offer = null
	}

	/** One look at where the title is, once a second: brings an offer up or away, and confirms a skip that was made. */
	suspend fun tick(movie: Double) {
		update(movie)
		confirmSkip(movie)
	}

	private fun update(movie: Double) {
		skippedIntroEnd?.let { if (movie >= it - 1) skippedIntroEnd = null }
		val wanted = offerAt(movie, intro, credits, skippedIntroEnd != null, creditsDismissed, upcoming)
		if (wanted is Offer.UpNext && atCredits == AtCredits.PlayNow) {
			// Binge mode: no card. The flag keeps later looks from repeating the jump; the next title clears it.
			if (offer != null || !steady() || held()) return
			creditsDismissed = true
			Diag.notice(DiagCategory.Offers, "autoplay.now", "next" to wanted.next.ratingKey, "at" to movie)
			advance()
			return
		}
		if (wanted == offer) return
		Diag.info(
			DiagCategory.Offers, "offer",
			"from" to name(offer), "to" to name(wanted), "at" to movie, "skipped" to skippedIntroEnd, "introCancelled" to introCancelled,
		)
		cancelCountdown("offer-changed")
		offer = wanted
		startCountdownIfDue()
	}

	/** The player's state changed: a countdown starts once playback runs steadily and stops when it does not. */
	fun stateChanged(playing: Boolean) {
		if (steady()) {
			startCountdownIfDue()
		} else if (offer is Offer.UpNext) {
			interrupt()
		} else {
			cancelCountdown(if (playing) "buffering" else "paused")
		}
	}

	private fun startCountdownIfDue() {
		if (countdown != null || !steady()) return
		when (offer) {
			is Offer.SkipIntro -> if (introDelay > 0 && !introCancelled) run(introDelay) { skipIntro(auto = true) }
			is Offer.UpNext -> if (atCredits == AtCredits.Countdown && !countdownStopped && !held()) run(creditsSeconds, advance)
			null -> Unit
		}
	}

	private fun run(seconds: Int, then: () -> Unit) {
		Diag.info(DiagCategory.Offers, "countdown.start", "offer" to name(offer), "seconds" to seconds)
		countdown = Countdown(SystemClock.elapsedRealtime(), seconds * 1000L)
		countdownJob = scope.launch {
			delay(seconds * 1000L)
			// Let go of the job first: the action's own cancel would otherwise cancel the job it runs in.
			countdownJob = null
			countdown = null
			Diag.notice(DiagCategory.Offers, "countdown.fire", "offer" to name(offer))
			then()
		}
	}

	private fun cancelCountdown(reason: String) {
		if (countdown != null) Diag.info(DiagCategory.Offers, "countdown.cancel", "reason" to reason, "offer" to name(offer))
		countdownJob?.cancel()
		countdownJob = null
		countdown = null
	}

	/** Pause, buffering, menus and any input stop the Up Next countdown until the next episode. */
	fun interrupt() {
		if (offer !is Offer.UpNext || countdown == null) return
		cancelCountdown("interrupted")
		countdownStopped = true
	}

	/** `auto`: the countdown ran out, rather than a tap. The skip is confirmed once playback runs on. */
	fun skipIntro(auto: Boolean = false) {
		val shown = offer as? Offer.SkipIntro
		if (shown == null) {
			Diag.notice(DiagCategory.Offers, "skip.ignored", "auto" to auto, "offer" to name(offer))
			return
		}
		Diag.notice(DiagCategory.Offers, "skip", "auto" to auto, "end" to shown.end, "playing" to steady())
		cancelCountdown("skip")
		if (!seek(shown.end)) {
			// Nothing moved, so the offer stays to be taken again.
			Diag.warning(DiagCategory.Offers, "skip.refused", "end" to shown.end)
			return
		}
		offer = null
		skippedIntroEnd = shown.end
		checking = shown.end
		checkBegun = false
		checkedAt = null
	}

	/** The viewer chose to watch the intro: no countdown takes it away. */
	fun watchIntro() {
		Diag.info(DiagCategory.Offers, "watch-intro")
		introCancelled = true
		cancelCountdown("watch-intro")
	}

	/** The TV's Watch/Back action dismisses this marker without seeking. */
	fun dismissIntro() {
		val shown = offer as? Offer.SkipIntro ?: return
		watchIntro()
		skippedIntroEnd = shown.end
		offer = null
	}

	/** The viewer chose to watch the credits: Up Next stays away and the episode ends on its own screen. */
	fun watchCredits() {
		Diag.info(DiagCategory.Offers, "watch-credits")
		creditsDismissed = true
		cancelCountdown("watch-credits")
		offer = null
	}

	/**
	 * Confirms an intro skip once playback has run on (Core's `SkipCheck`). A miss is tried once more; a second miss
	 * brings the Skip Intro offer back without its countdown, so a lost skip never leaves the intro playing with
	 * nothing to press.
	 */
	private suspend fun confirmSkip(movie: Double) {
		val target = checking ?: return
		val id = session ?: return
		try {
			if (!checkBegun) {
				core.playSkipBegin(id, target)
				checkBegun = true
			}
			val step = checkedAt?.let { movie - it } ?: 0.0
			checkedAt = movie
			// Only steady playback counts: a jump is a seek or a new stream.
			val advanced = if (step > 0 && step <= 2) step else 0.0
			val answer = core.playSkipCheck(id, movie, advanced, pending = !steady())
			when (answer.verdict) {
				SkipVerdict.None -> checking = null
				SkipVerdict.Waiting -> Unit
				SkipVerdict.Landed -> {
					checking = null
					Diag.info(DiagCategory.Offers, "skip.landed", "at" to movie, "end" to target, "retried" to answer.retried)
				}
				SkipVerdict.Retry -> {
					Diag.warning(DiagCategory.Offers, "skip.missed", "at" to movie, "end" to target)
					checkedAt = null
					if (!seek(target)) Diag.warning(DiagCategory.Offers, "skip.retry.refused", "end" to target)
				}
				SkipVerdict.Missed -> {
					checking = null
					skippedIntroEnd = null
					introCancelled = true
					Diag.error(DiagCategory.Offers, "skip.gave-up", "at" to movie, "end" to target)
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			checking = null
			Diag.warning(DiagCategory.Offers, "skip.check.failed", "error" to e.javaClass.simpleName)
		}
	}

	private fun name(offer: Offer?) = when (offer) {
		is Offer.SkipIntro -> "skipIntro"
		is Offer.UpNext -> "upNext"
		null -> "none"
	}
}

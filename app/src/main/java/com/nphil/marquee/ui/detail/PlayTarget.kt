package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.episodeCode
import com.nphil.marquee.core.minutesLeft
import com.nphil.marquee.core.progress

/** The share of a title after which the server plays it from the start instead of resuming. */
private const val RESUME_LIMIT = 0.95f

/** Partway in, and short of the end: Play resumes it. */
internal val PlexItem.isResumable: Boolean get() = progress?.let { it < RESUME_LIMIT } == true

/**
 * What the Play button plays on [page]: a film or an episode plays itself, a show the episode up next, a season its
 * first one to watch. Null where nothing plays yet, such as a season whose episodes are still loading.
 */
internal fun playTarget(page: PlexItem, state: DetailState): PlexItem? = when (page.type) {
	MediaType.Movie, MediaType.Episode -> page
	MediaType.Show -> page.onDeck ?: state.episodes?.let(::toWatch)
	MediaType.Season -> state.episodes?.let(::toWatch)
	else -> null
}

private fun toWatch(episodes: List<PlexItem>): PlexItem? =
	episodes.firstOrNull { it.isResumable } ?: episodes.firstOrNull { !it.isWatched } ?: episodes.firstOrNull()

/** The button's words: "Play" or "Resume · 42 min left" for a film or episode, "Play S1 · E3" or "Resume S2 · E5" for a show or season. */
internal fun playLabel(target: PlexItem, page: PlexItem): String {
	val verb = if (target.isResumable) "Resume" else "Play"
	if (page.type == MediaType.Movie || page.type == MediaType.Episode) {
		val left = target.minutesLeft?.takeIf { target.isResumable }
		return if (left != null) "$verb · ${ItemText.left(left)}" else verb
	}
	return listOfNotNull(verb, target.episodeCode).joinToString(" ")
}

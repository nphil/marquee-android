package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.ArrKind
import com.nphil.marquee.core.Deletion
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.ServiceCleanup
import com.nphil.marquee.core.WatchlistCleanup

/** What the delete dialog, the alert and the toast say, in the iPhone's words. */
internal object DeleteWording {
	/** Radarr holds movies; Sonarr holds shows, and so their seasons and episodes. */
	fun arrKind(item: PlexItem): ArrKind = if (item.type == MediaType.Movie) ArrKind.Radarr else ArrKind.Sonarr

	/**
	 * The connected services that also drop a movie or show: Radarr or Sonarr, and Seerr. A season or an episode names
	 * none, because they hear of it only when it was the show's last.
	 */
	fun places(item: PlexItem, arrConnected: Boolean, seerrConnected: Boolean): List<String> {
		if (item.type != MediaType.Movie && item.type != MediaType.Show) return emptyList()
		return listOfNotNull(arrKind(item).title.takeIf { arrConnected }, SEERR.takeIf { seerrConnected })
	}

	/** The confirmation's text: what leaves the server's disk and, in [places], where else the title is dropped. */
	fun message(item: PlexItem, places: List<String>): String {
		val files = when (item.type) {
			MediaType.Show -> "Every season and episode, and their files, are removed from the server's disk."
			MediaType.Season -> "Every episode in this season, and their files, are removed from the server's disk."
			else -> "Its files are removed from the server's disk."
		}
		if (places.isEmpty()) return "$files This can't be undone."
		return "$files It is also removed from ${list(places)}, so it isn't downloaded again. This can't be undone."
	}

	/** What a delete left undone after the server dropped the title, one line each; empty when nothing is left to say. */
	fun shortfall(result: Deletion, item: PlexItem): List<String> {
		val kind = arrKind(item)
		val arrId = if (kind == ArrKind.Radarr) "TMDB" else "TVDB"
		val lines = mutableListOf<String>()
		if (result.watchlist == WatchlistCleanup.Failed) lines += "It is still on your Watchlist, which can request it again."
		when (result.arr) {
			ServiceCleanup.Failed -> lines += "${kind.title} didn't answer, so it may still hold the title and download it again."
			ServiceCleanup.Refused -> lines += "${kind.title} refused the API key. Check it in Settings, under Integrations."
			ServiceCleanup.Unmatched -> lines += "${kind.title} was not asked: the server has no $arrId id for this title."
			ServiceCleanup.NotConnected, ServiceCleanup.NotApplicable, ServiceCleanup.Removed -> Unit
		}
		when (result.requests) {
			ServiceCleanup.Failed -> lines += "$SEERR didn't answer, so it may still show the title as requested."
			ServiceCleanup.Refused -> lines += "$SEERR wouldn't remove it: the signed-in account can't manage requests."
			ServiceCleanup.Unmatched -> lines += "$SEERR was not asked: the server has no TMDB id for this title."
			ServiceCleanup.NotConnected, ServiceCleanup.NotApplicable, ServiceCleanup.Removed -> Unit
		}
		return lines
	}

	/**
	 * The toast when no step failed: the title, the services that dropped it and, for a movie or show, the ones that
	 * were not asked because they are not set up, so the title may come back through them.
	 */
	fun summary(result: Deletion, item: PlexItem): String {
		val kind = arrKind(item)
		val dropped = listOfNotNull(
			kind.title.takeIf { result.arr == ServiceCleanup.Removed },
			SEERR.takeIf { result.requests == ServiceCleanup.Removed },
		)
		val deleted = "Deleted “${item.title}”"
		val sentences = mutableListOf(if (dropped.isEmpty()) deleted else "$deleted and removed it from ${list(dropped)}")
		if (item.type == MediaType.Movie || item.type == MediaType.Show) {
			val idle = listOfNotNull(
				kind.title.takeIf { result.arr == ServiceCleanup.NotConnected },
				SEERR.takeIf { result.requests == ServiceCleanup.NotConnected },
			)
			if (idle.isNotEmpty()) sentences += "${list(idle)} ${if (idle.size == 1) "wasn't" else "weren't"} asked: not set up in Settings."
		}
		return sentences.joinToString(". ")
	}

	/** "A", "A and B", "A, B, and C". */
	fun list(names: List<String>): String = when (names.size) {
		0, 1 -> names.joinToString()
		2 -> "${names[0]} and ${names[1]}"
		else -> "${names.dropLast(1).joinToString(", ")}, and ${names.last()}"
	}

	private const val SEERR = "Seerr"
}

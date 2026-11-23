package com.nphil.marquee.ui.detail

import com.nphil.marquee.core.ArrKind
import com.nphil.marquee.core.DeleteOutcome
import com.nphil.marquee.core.Deletion
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.ServiceCleanup
import com.nphil.marquee.core.WatchlistCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteWordingTest {
	private val movie = PlexItem(ratingKey = "1", type = MediaType.Movie, title = "Dune")
	private val show = PlexItem(ratingKey = "2", type = MediaType.Show, title = "Severance")
	private val episode = PlexItem(ratingKey = "3", type = MediaType.Episode, title = "Hello, Ms. Cobel")

	private fun deleted(
		arr: ServiceCleanup = ServiceCleanup.NotConnected,
		requests: ServiceCleanup = ServiceCleanup.NotConnected,
		watchlist: WatchlistCleanup = WatchlistCleanup.Unchanged,
	) = Deletion(DeleteOutcome.Deleted, watchlist = watchlist, arr = arr, requests = requests)

	@Test
	fun aMovieIsRadarrsAndEverythingElseSonarrs() {
		assertEquals(ArrKind.Radarr, DeleteWording.arrKind(movie))
		assertEquals(ArrKind.Sonarr, DeleteWording.arrKind(show))
		assertEquals(ArrKind.Sonarr, DeleteWording.arrKind(episode))
	}

	@Test
	fun theQuestionNamesTheServicesThatAlsoDropAMovieOrShow() {
		assertEquals(listOf("Radarr", "Seerr"), DeleteWording.places(movie, arrConnected = true, seerrConnected = true))
		assertEquals(listOf("Seerr"), DeleteWording.places(show, arrConnected = false, seerrConnected = true))
		assertTrue(DeleteWording.places(episode, arrConnected = true, seerrConnected = true).isEmpty())
		assertEquals(
			"Its files are removed from the server's disk. It is also removed from Radarr and Seerr, so it isn't downloaded again. This can't be undone.",
			DeleteWording.message(movie, listOf("Radarr", "Seerr")),
		)
		assertEquals(
			"Every season and episode, and their files, are removed from the server's disk. This can't be undone.",
			DeleteWording.message(show, emptyList()),
		)
	}

	@Test
	fun theToastNamesWhereTheTitleWasDropped() {
		val result = deleted(arr = ServiceCleanup.Removed, requests = ServiceCleanup.Removed)
		assertEquals("Deleted “Dune” and removed it from Radarr and Seerr", DeleteWording.summary(result, movie))
	}

	@Test
	fun theToastSaysWhichServicesWereNotAskedBecauseTheyAreNotSetUp() {
		assertEquals(
			"Deleted “Dune”. Radarr and Seerr weren't asked: not set up in Settings.",
			DeleteWording.summary(deleted(), movie),
		)
		assertEquals(
			"Deleted “Severance” and removed it from Seerr. Sonarr wasn't asked: not set up in Settings.",
			DeleteWording.summary(deleted(requests = ServiceCleanup.Removed), show),
		)
		// Radarr or Sonarr hears of an episode only when it was the show's last, so a delete of one says nothing of them.
		assertEquals("Deleted “Hello, Ms. Cobel”", DeleteWording.summary(deleted(), episode))
	}

	@Test
	fun theAlertListsEachStepThatLeftTheTitleBehind() {
		val result = deleted(arr = ServiceCleanup.Failed, requests = ServiceCleanup.Refused, watchlist = WatchlistCleanup.Failed)
		assertEquals(
			listOf(
				"It is still on your Watchlist, which can request it again.",
				"Radarr didn't answer, so it may still hold the title and download it again.",
				"Seerr wouldn't remove it: the signed-in account can't manage requests.",
			),
			DeleteWording.shortfall(result, movie),
		)
		assertEquals(
			listOf(
				"Sonarr refused the API key. Check it in Settings, under Integrations.",
				"Seerr was not asked: the server has no TMDB id for this title.",
			),
			DeleteWording.shortfall(deleted(arr = ServiceCleanup.Refused, requests = ServiceCleanup.Unmatched), show),
		)
		assertEquals(
			listOf("Radarr was not asked: the server has no TMDB id for this title."),
			DeleteWording.shortfall(deleted(arr = ServiceCleanup.Unmatched), movie),
		)
	}

	@Test
	fun nothingIsLeftToSayWhenEveryStepWasDoneOrNotNeeded() {
		val done = deleted(arr = ServiceCleanup.NotApplicable, requests = ServiceCleanup.Removed, watchlist = WatchlistCleanup.Removed)
		assertTrue(DeleteWording.shortfall(done, show).isEmpty())
		assertTrue(DeleteWording.shortfall(deleted(), movie).isEmpty())
	}
}

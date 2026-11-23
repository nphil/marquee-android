package com.nphil.marquee.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDockTest {
	private val film = PlayRequest.Title("42")
	private val episode = PlayRequest.Title("77", titleKey = "7")

	@Test
	fun aNewDockHasNothingToPlay() {
		val dock = PlayerDock(SavedStateHandle())
		assertEquals(DockState.Closed, dock.state)
		assertNull(dock.session)
	}

	@Test
	fun openingATitleTakesTheFullScreen() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		assertEquals(DockState.Full, dock.state)
		assertEquals(film, dock.session?.request)
	}

	@Test
	fun theSameTitleOpenedAgainReturnsToFullScreenWithoutRestarting() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		val first = dock.session
		dock.minimise()
		dock.open(film)
		assertEquals(DockState.Full, dock.state)
		assertSame(first, dock.session)
	}

	@Test
	fun anotherTitleReplacesTheSessionAndTakesTheFullScreen() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		val first = dock.session!!
		dock.minimise()
		dock.open(episode)
		assertEquals(DockState.Full, dock.state)
		assertEquals(episode, dock.session?.request)
		assertNotEquals(first.id, dock.session?.id)
	}

	@Test
	fun minimisingAndExpandingMoveBetweenFullAndBarWithoutNewSession() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		val session = dock.session
		dock.minimise()
		assertEquals(DockState.Bar, dock.state)
		dock.expand()
		assertEquals(DockState.Full, dock.state)
		assertSame(session, dock.session)
	}

	@Test
	fun aScreenThatIsLeavingCannotCloseTheSessionThatReplacedIt() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		val old = dock.session!!.id
		dock.open(episode)
		dock.close(old)
		assertEquals(DockState.Full, dock.state)
		assertEquals(episode, dock.session?.request)
		dock.close(dock.session!!.id)
		assertEquals(DockState.Closed, dock.state)
		assertNull(dock.session)
	}

	@Test
	fun closingWithoutAnIdClosesWhateverPlays() {
		val dock = PlayerDock(SavedStateHandle())
		dock.open(film)
		dock.minimise()
		dock.close()
		assertEquals(DockState.Closed, dock.state)
		assertNull(dock.session)
	}

	@Test
	fun whatWasPlayingComesBackFullScreenAfterTheProcessRestarts() {
		val saved = SavedStateHandle()
		val request = PlayRequest.Title("42", fromStart = true, downloadId = "d1")
		PlayerDock(saved).open(request)
		val restored = PlayerDock(saved)
		assertEquals(DockState.Full, restored.state)
		assertEquals(request, restored.session?.request)
	}

	@Test
	fun aClosedPlayerIsNotRestored() {
		val saved = SavedStateHandle()
		val dock = PlayerDock(saved)
		dock.open(film)
		dock.close()
		assertNull(PlayerDock(saved).session)
	}

	@Test
	fun everyKindOfRequestReadsBackAsItWasWritten() {
		val requests = listOf(
			film,
			PlayRequest.Title("77", fromStart = true, titleKey = "7", downloadId = "d1", recordingMediaID = 9),
			PlayRequest.Channel("scope", "channel"),
		)
		for (request in requests) assertEquals(request, decodedPlayRequest(request.encoded()))
	}

	@Test
	fun aRequestThatNoLongerReadsIsNone() {
		assertNull(decodedPlayRequest(null))
		assertNull(decodedPlayRequest("{"))
		assertNull(decodedPlayRequest("""{"type":"gone"}"""))
	}

	@Test
	fun releasingASessionClearsWhatItsOwnerHoldsAndNothingElse() {
		val dock = PlayerDock(SavedStateHandle())
		val mine = ViewModelProvider(dock.owner(1), make)[Probe::class.java]
		val other = ViewModelProvider(dock.owner(2), make)[Probe::class.java]
		assertSame(mine, ViewModelProvider(dock.owner(1), make)[Probe::class.java])
		dock.release(1)
		assertTrue(mine.cleared)
		assertFalse(other.cleared)
	}

	private class Probe : ViewModel() {
		var cleared = false

		override fun onCleared() {
			cleared = true
		}
	}

	private val make = object : ViewModelProvider.Factory {
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
	}
}

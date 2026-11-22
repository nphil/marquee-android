package com.nphil.marquee.browse

import androidx.compose.runtime.Immutable
import com.nphil.marquee.core.LibraryChanges
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.data.BrowsePreferences
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ToastCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** The server and profile browsed now. [profile] is the profile's uuid; empty for the account itself. */
@Immutable
data class BrowsePlace(val server: PlexServer, val profile: String, val profileName: String?) {
	/** Names this place: another server or profile starts every screen afresh. */
	val key: String get() = "${server.id}/$profile"
}

/**
 * What the browse screens ask of the signed-in session. The app answers from [AppSession]; a test answers for a
 * server it started itself.
 */
interface BrowseSource {
	/** Where browsing is now; null while there is no server. */
	val place: StateFlow<BrowsePlace?>

	/** The profile's plex.tv token, for what belongs to the person rather than a server: the Watchlist. Null when signed out. */
	val accountToken: String?

	/**
	 * Runs [block] for the current server and the address that answered last. A rejected sign-in ends the session
	 * here, so the screens that call through here need not.
	 */
	suspend fun <T> onServer(block: suspend MarqueeCore.(server: PlexServer, remembered: String?) -> T): T

	/** Runs [block] with the profile's plex.tv token, for what is the person's rather than a server's; null when signed out. */
	suspend fun <T> onAccount(block: suspend MarqueeCore.(token: String) -> T): T?
}

/** [BrowseSource] over the app's session. */
class SessionBrowseSource(
	private val session: AppSession,
	private val core: MarqueeCore,
	scope: CoroutineScope,
) : BrowseSource {
	override val place: StateFlow<BrowsePlace?> = session.state
		.map(::placeOf)
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.Eagerly, placeOf(session.state.value))

	override val accountToken: String? get() = session.accountToken

	override suspend fun <T> onServer(block: suspend MarqueeCore.(server: PlexServer, remembered: String?) -> T): T =
		session.onServer { server, remembered -> core.block(server, remembered) }

	override suspend fun <T> onAccount(block: suspend MarqueeCore.(token: String) -> T): T? =
		session.accountToken?.let { core.block(it) }

	private fun placeOf(state: AppSession.State): BrowsePlace? =
		state.server?.let { BrowsePlace(it, state.user?.uuid.orEmpty(), state.user?.title) }
}

/** Everything the browse screens and their models share, built once from the app's container and by a test from its fakes. */
class BrowseEnvironment(
	val source: BrowseSource,
	val preferences: BrowsePreferences,
	val changes: LibraryChanges,
	val toasts: ToastCenter,
	/** The folder of the saved answers Home and the Watchlist show first on the next launch; see [Snapshots]. */
	val snapshots: File,
	/** Outlives the screens: work that must finish once the person has left, such as telling the server a card is gone after its message ends. */
	val scope: CoroutineScope,
)

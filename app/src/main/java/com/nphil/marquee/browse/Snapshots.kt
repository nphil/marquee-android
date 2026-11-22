package com.nphil.marquee.browse

import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.SnapshotSpec
import com.nphil.marquee.core.removeSnapshots
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.session.AppSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The saved answers Home and the Watchlist show first on the next launch, while the server is asked again. MarqueeCore
 * writes them in the app's cache, one scope per server and profile.
 */
object Snapshots {
	fun directory(cache: File) = File(cache, "HomeSnapshots")

	/**
	 * Forgets a profile's saved answers when the tablet switches away from it and every profile's at sign-out, so a
	 * profile never opens on another's titles. Runs for the life of the app.
	 */
	suspend fun forgetWhenLeft(session: AppSession, core: MarqueeCore, directory: File) {
		var signedIn = false
		var profile: String? = null
		session.state.map { it.hasSignIn to it.user?.uuid.orEmpty() }.distinctUntilChanged().collect { (hasSignIn, who) ->
			if (!hasSignIn) {
				if (signedIn) forget(core, directory, null)
				signedIn = false
				profile = null
			} else {
				profile?.takeIf { it != who }?.let { forget(core, directory, it) }
				signedIn = true
				profile = who
			}
		}
	}

	private suspend fun forget(core: MarqueeCore, directory: File, profile: String?) {
		try {
			core.removeSnapshots(directory.path, profile)
		} catch (e: CoreException) {
			Diag.warning(DiagCategory.Plex, "snapshots.forget.failed", "kind" to e.kind.name)
		}
	}
}

/** Where this place's saved answers live: in [directory], under its server and profile. */
fun BrowsePlace.snapshot(directory: File) = SnapshotSpec(directory.path, server.id, profile)

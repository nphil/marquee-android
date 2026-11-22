package com.nphil.marquee.seerr

import com.nphil.marquee.core.CoreException

/** The words for what can go wrong with Seerr, shared by the settings page and Requests. */
object SeerrText {
	private const val UNREACHABLE =
		"Can't reach Seerr. No answer from that address. Check it, and that this tablet is on the same network."

	/** What is wrong with an address or key that [com.nphil.marquee.core.seerrParse] named. */
	fun problem(problem: String?): String? = when (problem) {
		null -> null
		"scheme" -> "The address must start with https:// or http://."
		"host" -> "That is not a valid address."
		"plaintext" -> "Plain http:// is only for addresses on your home network. Use https:// for any other."
		"key" -> "That is not a valid API key."
		else -> "That is not a valid address."
	}

	/** A problem is shown once the field is left, and not for a field with nothing in it yet. */
	fun shownProblem(problem: String?, text: String, focused: Boolean): String? =
		if (focused || text.isBlank()) null else problem(problem)

	/** The failure as a sentence for the screen; [error] is what a Seerr call threw. */
	fun failure(error: Throwable): String {
		val core = error as? CoreException
		if (core?.kind == CoreException.Kind.Unreachable) return UNREACHABLE
		if (core?.kind != CoreException.Kind.Seerr) return "Couldn't reach Seerr. Try again."
		return when (core.failure) {
			"unreachable" -> UNREACHABLE
			"timedOut" -> "Seerr took too long to answer. Try again in a moment."
			"notSeerr" -> "That is not Seerr. The address answers, but not like Seerr. Check the address."
			"badKey" -> "Seerr refused the key. The address answers, but the API key is wrong. Copy it again from Seerr."
			"notSignedIn" -> "Sign in to Plex first. Seerr signs in with the Plex profile in use."
			"plexAccountNotAllowed" ->
				"Seerr doesn't let this Plex profile in. Seerr's admin can import it under Users, or sign in with an API key instead."
			"plexSignInFailed" ->
				"Seerr couldn't check this profile with Plex. Plex sign-in may be off in Seerr; an API key works instead."
			"notAllowed" -> "This account may not make requests in Seerr."
			"quotaReached" -> "This account has used up its requests for now. Seerr's admin sets the limit."
			"alreadyRequested" -> "Already requested. Everything asked for is requested or available."
			"notFound" -> "Seerr no longer has this title."
			// Without a message from Seerr the exception's own text is the kind.
			"failed" -> core.message?.takeIf { it != "seerr" }?.let { "Seerr answered with an error: $it" } ?: "Seerr answered with an error."
			else -> "Couldn't reach Seerr. Try again."
		}
	}

	/** Whether a request found everything asked for already requested or available. */
	fun alreadyRequested(error: Throwable): Boolean =
		error is CoreException && error.kind == CoreException.Kind.Seerr && error.failure == "alreadyRequested"

	/** Which field a [problem] belongs to: the key's own, or the address's. */
	fun isKeyProblem(problem: String?): Boolean = problem == "key"

	/** How the last connection test went, for the line under Test Connection. */
	fun status(configured: Boolean, check: Check): Line = when {
		!configured -> Line(Tone.Neutral, "Not set up. Add Seerr's address, then test the connection.")
		else -> when (check) {
			Check.Untested -> Line(Tone.Neutral, "Not tested yet.")
			Check.Testing -> Line(Tone.Neutral, "Checking… Signing in to Seerr.")
			is Check.Connected -> Line(Tone.Good, "Connected · Seerr ${check.version}, signed in as ${check.user}.")
			is Check.Failed -> Line(Tone.Problem, check.text)
		}
	}

	enum class Tone { Neutral, Good, Problem }

	data class Line(val tone: Tone, val text: String)

	/** A connection test's progress and result. */
	sealed interface Check {
		data object Untested : Check

		data object Testing : Check

		data class Connected(val version: String, val user: String) : Check

		data class Failed(val text: String) : Check
	}

	const val INTRO =
		"Seerr takes requests for movies and series and hands them to Radarr and Sonarr. With its address, Requests can find titles and ask for them."
	const val PLEX_NOTE =
		"Signs in as the Plex profile in use, like Seerr's own Sign In with Plex, so requests are that profile's."
	const val KEY_NOTE =
		"Requests are made as the key's owner, usually Seerr's admin. The key is in Seerr under Settings, General."
	const val PULSARR_NOTE =
		"Turn on when Pulsarr requests whatever goes on a Plex Watchlist. Title pages then say when adding a title to the Watchlist requests it too."
	const val FORGET_TITLE = "Forget Seerr?"
	const val FORGET_MESSAGE = "The address, sign-in and API key are removed from this tablet, and Requests goes away."
}

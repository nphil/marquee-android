package com.nphil.marquee.ui.signin

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.MarqueeMark
import com.nphil.marquee.ui.components.PillButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.settings.SignOutDialog
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/**
 * Sign-in (DESIGN §8.0): the Marquee mark and one Sign in button, which opens plex.tv's sign-in page in a
 * Custom Tab. Without a browser that opens Custom Tabs, or on request, a plex.tv/link code takes its place.
 * Waiting, an expired PIN and a refused or unreachable sign-in take the button's place on the same screen.
 */
@Composable
fun SignInScreen(session: AppSession) {
	val state by session.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val start = {
		if (PlexSignInBrowser.isAvailable(context)) session.signIn() else session.signInWithCode()
	}
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(lucent.canvas)
			.verticalScroll(rememberScrollState())
			.safeDrawingPadding()
			.padding(horizontal = Space.inset, vertical = Space.s7),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s7, Alignment.CenterVertically),
	) {
		MarqueeMark(Modifier.widthIn(max = 240.dp).fillMaxWidth())
		Text("Marquee", style = MaterialTheme.typography.displaySmall, color = lucent.ink)
		val content = when (val phase = state.phase) {
			AppSession.Phase.SigningIn -> {
				val code = state.linkCode
				when {
					code != null -> Content.Code(code)
					state.signInPage != null -> Content.Page
					else -> Content.Waiting
				}
			}
			is AppSession.Phase.Failed -> Content.Failure(phase.error)
			else -> Content.Start
		}
		val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
		AnimatedContent(
			targetState = content,
			modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
			transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
			contentAlignment = Alignment.Center,
			label = "sign-in",
		) { shown ->
			when (shown) {
				Content.Start -> PillButton("Sign in", onClick = start)
				Content.Waiting -> Waiting(onCancel = session::cancelSignIn)
				Content.Page -> PagePanel(session)
				is Content.Code -> CodePanel(shown.code, onCancel = session::cancelSignIn)
				is Content.Failure -> Failure(shown.error, state.canRetry, state.needsProfileVerification, session, onSignIn = start)
			}
		}
	}
}

private sealed interface Content {
	data object Start : Content
	data object Waiting : Content
	data object Page : Content
	data class Code(val code: String) : Content
	data class Failure(val error: CoreException.Kind) : Content
}

@Composable
private fun Waiting(onCancel: () -> Unit) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		LoadingIndicator()
		Text("Signing in", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
		TextAction("Cancel", onClick = onCancel)
	}
}

/** While plex.tv's page is open; the app comes back over it once the PIN is approved there. */
@Composable
private fun PagePanel(session: AppSession) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		LoadingIndicator()
		Text(
			"Sign in to Plex on the page that opened. Marquee comes back here by itself once that's done.",
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink2,
			textAlign = TextAlign.Center,
		)
		TextAction("Open the sign-in page again", onClick = session::reopenSignInPage)
		TextAction("Use a code instead", onClick = session::signInWithCode)
		TextAction("Cancel", onClick = session::cancelSignIn)
	}
}

@Composable
private fun CodePanel(code: String, onCancel: () -> Unit) {
	val context = LocalContext.current
	val shape = RoundedCornerShape(Radius.card)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(lucent.glass, shape)
			.border(BorderStroke(1.dp, lucent.edge), shape)
			.padding(Space.s6),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		Text(
			"On any device, go to plex.tv/link and enter",
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink2,
			textAlign = TextAlign.Center,
		)
		SelectionContainer {
			Text(
				code,
				style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 0.18.em),
				color = lucent.ink,
				modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") },
			)
		}
		TextAction("Open plex.tv/link", onClick = {
			try {
				context.startActivity(Intent(Intent.ACTION_VIEW, "https://plex.tv/link".toUri()))
			} catch (_: ActivityNotFoundException) {
				// No browser on this tablet: the code is still there to enter on another device.
			}
		})
		TextAction("Cancel", onClick = onCancel)
	}
}

@Composable
private fun Failure(error: CoreException.Kind, canRetry: Boolean, verifyProfile: Boolean, session: AppSession, onSignIn: () -> Unit) {
	var confirmingSignOut by remember { mutableStateOf(false) }
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Icon(
				painterResource(if (error == CoreException.Kind.Unreachable) R.drawable.ic_cloud_off else R.drawable.ic_person),
				contentDescription = null,
				tint = lucent.ink,
			)
			val note = if (verifyProfile && canRetry) "Reconnect to Plex once to verify the saved profile. Downloads stay on this device."
				else message(error)
			Text(note, style = MaterialTheme.typography.bodyLarge, color = lucent.ink, textAlign = TextAlign.Center)
		}
		when {
			error == CoreException.Kind.PinExpired || error == CoreException.Kind.Unauthorized ->
				PillButton("Sign in again", onClick = onSignIn)
			canRetry -> PillButton("Try again", onClick = session::retry)
			else -> PillButton("Try again", onClick = onSignIn)
		}
		if (canRetry) TextAction("Sign Out", onClick = { confirmingSignOut = true })
	}
	if (confirmingSignOut) {
		SignOutDialog(onConfirm = session::signOut, onDismiss = { confirmingSignOut = false })
	}
}

private fun message(error: CoreException.Kind) = when (error) {
	CoreException.Kind.PinExpired -> "The sign-in expired."
	CoreException.Kind.Unauthorized -> "Plex signed this tablet out."
	CoreException.Kind.Unreachable, CoreException.Kind.NoConnections -> "Plex can't be reached. Check the connection."
	else -> "Sign-in didn't finish."
}

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
import androidx.compose.foundation.layout.size
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
 * Sign-in (DESIGN §8.0): the Marquee mark, then a plex.tv/link code to enter on any device. Waiting, an
 * expired code and a refused or unreachable sign-in take the button's place on the same screen.
 */
@Composable
fun SignInScreen(session: AppSession) {
	val state by session.state.collectAsStateWithLifecycle()
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
			AppSession.Phase.SigningIn -> state.linkCode?.let { Content.Code(it) } ?: Content.Waiting
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
				Content.Start -> PillButton("Sign in", onClick = session::signIn)
				Content.Waiting -> Waiting(onCancel = session::cancelSignIn)
				is Content.Code -> CodePanel(shown.code, onCancel = session::cancelSignIn)
				is Content.Failure -> Failure(shown.error, canRetry = state.canRetry, session = session)
			}
		}
	}
}

private sealed interface Content {
	data object Start : Content
	data object Waiting : Content
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
private fun Failure(error: CoreException.Kind, canRetry: Boolean, session: AppSession) {
	var confirmingSignOut by remember { mutableStateOf(false) }
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Icon(
				painterResource(if (error == CoreException.Kind.Unreachable) R.drawable.ic_cloud_off else R.drawable.ic_person),
				contentDescription = null,
				tint = lucent.ink,
			)
			Text(message(error), style = MaterialTheme.typography.bodyLarge, color = lucent.ink, textAlign = TextAlign.Center)
		}
		when {
			error == CoreException.Kind.PinExpired -> PillButton("Get a new code", onClick = session::signIn)
			error == CoreException.Kind.Unauthorized -> PillButton("Sign in again", onClick = session::signIn)
			canRetry -> PillButton("Try again", onClick = session::retry)
			else -> PillButton("Try again", onClick = session::signIn)
		}
		if (canRetry) TextAction("Sign Out", onClick = { confirmingSignOut = true })
	}
	if (confirmingSignOut) {
		SignOutDialog(onConfirm = session::signOut, onDismiss = { confirmingSignOut = false })
	}
}

private fun message(error: CoreException.Kind) = when (error) {
	CoreException.Kind.PinExpired -> "The code expired."
	CoreException.Kind.Unauthorized -> "Plex signed this tablet out."
	CoreException.Kind.Unreachable, CoreException.Kind.NoConnections -> "Plex can't be reached. Check the connection."
	else -> "Sign-in didn't finish."
}

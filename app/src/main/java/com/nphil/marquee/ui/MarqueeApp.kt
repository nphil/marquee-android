package com.nphil.marquee.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ProvidePlexImages
import com.nphil.marquee.ui.servers.ServerChoiceScreen
import com.nphil.marquee.ui.shell.Shell
import com.nphil.marquee.ui.signin.SignInScreen
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.lucent

private enum class RootScreen { SignIn, Servers, Shell }

/** Sign-in, then the server list, then the app: whichever the session's phase calls for. */
@Composable
fun MarqueeApp(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
	val state = container.session.state.collectAsStateWithLifecycle()
	val root by remember {
		derivedStateOf {
			when (state.value.phase) {
				AppSession.Phase.Ready -> RootScreen.Shell
				AppSession.Phase.ChoosingServer -> RootScreen.Servers
				else -> RootScreen.SignIn
			}
		}
	}
	LaunchedEffect(root) {
		when (root) {
			RootScreen.SignIn -> DiagScreen.tab("Sign In")
			RootScreen.Servers -> DiagScreen.tab("Servers")
			RootScreen.Shell -> Unit
		}
	}
	val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	ProvidePlexImages(container) {
		AnimatedContent(
			targetState = root,
			modifier = Modifier.fillMaxSize().background(lucent.canvas),
			transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
			label = "root",
		) { screen ->
			when (screen) {
				RootScreen.SignIn -> SignInScreen(container.session)
				RootScreen.Servers -> ServerChoiceScreen(container.session)
				RootScreen.Shell -> AppNavigation(container) { Shell(container, choice, onTheme) }
			}
		}
	}
}

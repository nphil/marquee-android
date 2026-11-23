package com.nphil.marquee.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.data.PosterLabels
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.browse.LocalPosterLabels
import com.nphil.marquee.ui.components.LocalHeaderStyle
import com.nphil.marquee.ui.components.LocalShowsScrollBars
import com.nphil.marquee.ui.components.ProvidePlexImages
import com.nphil.marquee.ui.link.LinkRemoteOverlay
import com.nphil.marquee.ui.servers.ServerChoiceScreen
import com.nphil.marquee.ui.shell.LocalShellMetrics
import com.nphil.marquee.ui.shell.Shell
import com.nphil.marquee.ui.shell.ShellMetrics
import com.nphil.marquee.ui.signin.SignInScreen
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.lucent
import com.nphil.marquee.bigscreen.GlassesAskDialog
import com.nphil.marquee.bigscreen.GlassesRemoteOverlay
import com.nphil.marquee.ui.tvremote.FloatingRemote

private enum class RootScreen { SignIn, Servers, Shell }

/**
 * Sign-in, then the server list, then the app: whichever the session's phase calls for. All of it shares one
 * [LocalShellMetrics], so an overlay beside the shell can read where the sidebar is.
 */
@Composable
fun MarqueeApp(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
	CompositionLocalProvider(LocalShellMetrics provides remember { ShellMetrics() }) {
		MarqueeScreens(container, choice, onTheme)
	}
}

@Composable
private fun MarqueeScreens(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
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
				RootScreen.Servers -> CompositionLocalProvider(
					LocalHeaderStyle provides choice.headerStyle,
					LocalShowsScrollBars provides choice.showsScrollBars,
				) { ServerChoiceScreen(container.session) }
				RootScreen.Shell -> {
					val profile = state.value.user?.uuid.orEmpty()
					val labels = key(profile) {
						remember(container, profile) { container.settings.posterLabels(profile) }
							.collectAsStateWithLifecycle(PosterLabels()).value
					}
					CompositionLocalProvider(
						LocalPosterLabels provides labels,
						LocalHeaderStyle provides choice.headerStyle,
						LocalShowsScrollBars provides choice.showsScrollBars,
					) {
						AppNavigation(container) { Shell(container, choice, onTheme) }
					}
				}
			}
		}
		FloatingRemote(container.tvRemote, visible = root == RootScreen.Shell)
		GlassesRemoteOverlay(container)
		GlassesAskDialog(container.glasses)
		LinkRemoteOverlay(container.link)
	}
}

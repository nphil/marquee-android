package com.nphil.marquee.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.ProvidePlexImages
import com.nphil.marquee.ui.library.LibraryScreen
import com.nphil.marquee.ui.library.LibraryViewModel
import com.nphil.marquee.ui.servers.ServerChoiceScreen
import com.nphil.marquee.ui.settings.SettingsScreen
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

private enum class Tab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
	Library("Library", R.drawable.ic_video_library, R.drawable.ic_video_library_filled),
	Settings("Settings", R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

/** Library and Settings in a bar, a rail or a drawer, as the window's size calls for. */
@Composable
private fun Shell(container: AppContainer, choice: ThemeChoice, onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit) {
	var tab by rememberSaveable { mutableStateOf(Tab.Library) }
	val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
	val libraryModel = viewModel { LibraryViewModel(container.session) }
	// Back from Settings returns to the Library before it leaves the app.
	BackHandler(enabled = tab != Tab.Library) { tab = Tab.Library }
	LaunchedEffect(tab) { DiagScreen.tab(tab.label) }
	NavigationSuiteScaffold(
		navigationItems = {
			Tab.entries.forEach { item ->
				val selected = tab == item
				NavigationSuiteItem(
					selected = selected,
					onClick = { tab = item },
					icon = {
						Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
					},
					label = { Text(item.label) },
					navigationSuiteType = type,
				)
			}
		},
		navigationSuiteType = type,
		containerColor = lucent.canvas,
	) {
		val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
		Crossfade(targetState = tab, animationSpec = effects, label = "tab") { shown ->
			when (shown) {
				Tab.Library -> LibraryScreen(container.session, libraryModel)
				Tab.Settings -> SettingsScreen(container.session, container.themes, choice, onTheme)
			}
		}
	}
}

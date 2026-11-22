package com.nphil.marquee.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.SeerrFeed
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.theme.lucent
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Requests: discover movies and series, look for one, and ask Seerr for it. It has its own back stack, so a title
 * opened from a list goes back to that list; the window's back gesture steps through it before it leaves Requests.
 * Only the owner's switch in Settings › Integrations › Seerr makes this reachable; the shell shows it while
 * [com.nphil.marquee.seerr.Seerr.enabled] is true.
 */
@Composable
fun RequestsDestination(container: AppContainer, modifier: Modifier = Modifier) {
	val config = container.seerr.config.collectAsStateWithLifecycle()
	val settings = config.value
	Box(modifier.fillMaxSize().background(lucent.canvas), contentAlignment = Alignment.Center) {
		when {
			!settings.loaded -> Unit
			!settings.enabled -> EmptyState(
				icon = R.drawable.ic_playlist_add,
				title = "Set up Seerr",
				message = "Add Seerr in Settings under Integrations to discover and request movies and series.",
			)
			else -> RequestsStack(container)
		}
	}
}

private val StackConfig = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclass(RequestsHome::class)
			subclass(RequestsBrowse::class)
			subclass(RequestsTitle::class)
		}
	}
}

/** Opens Requests' pages. */
@Stable
internal class RequestsNav(private val stack: NavBackStack<NavKey>) {
	fun browse(feed: SeerrFeed) = push(RequestsBrowse(feed))

	fun title(kind: SeerrKind, tmdbId: Int) = push(RequestsTitle(kind, tmdbId))

	fun back() {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
	}

	private fun push(route: RequestsRoute) {
		if (stack.lastOrNull() != route) stack.add(route)
	}
}

@Composable
private fun RequestsStack(container: AppContainer) {
	val stack = rememberNavBackStack(StackConfig, RequestsHome)
	val nav = remember(stack) { RequestsNav(stack) }
	NavDisplay(
		backStack = stack,
		onBack = nav::back,
		entryDecorators = listOf(
			rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
			rememberViewModelStoreNavEntryDecorator<NavKey>(),
		),
		entryProvider = entryProvider {
			entry<RequestsHome> { RequestsHomeScreen(container, nav) }
			entry<RequestsBrowse> { route -> BrowseScreen(container, route.feed, nav) }
			entry<RequestsTitle> { route -> TitleScreen(container, route.kind, route.tmdbId, nav) }
		},
	)
}

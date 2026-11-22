package com.nphil.marquee.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexRole
import com.nphil.marquee.core.copiesOf
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.ui.components.ToastHost
import com.nphil.marquee.ui.detail.DetailScreen
import com.nphil.marquee.ui.detail.ExtraScreen
import com.nphil.marquee.ui.detail.PersonScreen
import com.nphil.marquee.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Where the back stack can be. Each is saved with the stack, so a title's page survives the app being recreated. */
@Serializable
sealed interface Route : NavKey

/** The tabs: Library, Settings and the rest. */
@Serializable
data object Root : Route

/**
 * A title's page. [catalogue] is the Watchlist's own copy of a title the server does not have: that page has no
 * server to ask and shows what the Watchlist knows.
 */
@Serializable
data class Detail(val ratingKey: String, val catalogue: PlexItem? = null) : Route

/** Everything a cast member appears in on the server. */
@Serializable
data class Person(val id: String, val name: String) : Route

/** A trailer or other bonus video, full screen. */
@Serializable
data class Extra(val item: PlexItem) : Route

/** The back stack, for the screens that open pages. */
@Stable
class Navigator internal constructor(
	private val stack: NavBackStack<NavKey>,
	private val container: AppContainer,
	private val scope: CoroutineScope,
) {
	/** The title whose poster was tapped last. Only that poster joins the zoom to its page, so a title that two shelves list never shares a key twice. */
	var origin: String? by mutableStateOf(null)
		private set

	fun openItem(ratingKey: String) {
		origin = ratingKey
		push(Detail(ratingKey))
	}

	/** A Watchlist title: the server's own copy when it has one, else the Watchlist's page for it. */
	fun openWatchlistTitle(item: PlexItem) {
		scope.launch {
			val guid = item.guid
			val copies = if (guid == null) {
				emptyList()
			} else {
				try {
					container.session.onServer { server, remembered -> container.core.copiesOf(server, remembered, guid) }
				} catch (e: Exception) {
					Diag.warning(DiagCategory.Ui, "watchlist.match.failed", "error" to e.javaClass.simpleName)
					emptyList()
				}
			}
			val match = container.changes.state.value.shown(copies).firstOrNull()
			if (match != null) openItem(match.ratingKey) else push(Detail(item.ratingKey, catalogue = item))
		}
	}

	fun openPerson(role: PlexRole) {
		val id = role.id ?: return
		push(Person(id, role.name))
	}

	fun openExtra(item: PlexItem) = push(Extra(item))

	fun back() {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
	}

	private fun push(route: Route) {
		if (stack.lastOrNull() != route) stack.add(route)
	}
}

val LocalNavigator = staticCompositionLocalOf<Navigator?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The screen being shown's animation scope, which a shared element needs; null outside a page of the stack. */
internal val LocalEntryVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private data class PosterKey(val ratingKey: String)

/**
 * The art of a poster or card that opens a title's page: it grows into that page's hero as the page arrives and
 * shrinks back into it on the way out, including while a back swipe is under the finger. Only the poster that was
 * tapped takes part, so it is safe on every poster of a screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.posterSharedElement(ratingKey: String): Modifier {
	val navigator = LocalNavigator.current ?: return this
	if (navigator.origin != ratingKey) return this
	return zoom(ratingKey, Radius.tile)
}

/** The hero of the page for [ratingKey], where the tapped poster's art lands. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.heroSharedElement(ratingKey: String): Modifier = zoom(ratingKey, Radius.card)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.zoom(ratingKey: String, radius: androidx.compose.ui.unit.Dp): Modifier {
	val scope = LocalSharedTransitionScope.current ?: return this
	val visibility = LocalEntryVisibility.current ?: return this
	val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	return with(scope) {
		this@zoom.sharedBounds(
			sharedContentState = rememberSharedContentState(PosterKey(ratingKey)),
			animatedVisibilityScope = visibility,
			enter = fadeIn(effects),
			exit = fadeOut(effects),
			resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
			clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(radius)),
		)
	}
}

private val BackStack = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclass(Root::class)
			subclass(Detail::class)
			subclass(Person::class)
			subclass(Extra::class)
		}
	}
}

/**
 * The pages above the tabs: a title, a person, a video. [shell] is the tabs, given the [Navigator] that opens
 * pages. Back, and the back swipe, close a page and show what is under it as it goes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(container: AppContainer, shell: @Composable (Navigator) -> Unit) {
	val stack = rememberNavBackStack(BackStack, Root)
	val scope = rememberCoroutineScope()
	val navigator = remember(stack, container, scope) { Navigator(stack, container, scope) }
	val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
	// A message that offers Undo does its work when the app stops, as the iPhone's does.
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) { container.toasts.finish() }
	Box(Modifier.fillMaxSize()) {
		SharedTransitionLayout {
			CompositionLocalProvider(LocalNavigator provides navigator, LocalSharedTransitionScope provides this) {
				NavDisplay(
					backStack = stack,
					onBack = navigator::back,
					entryDecorators = listOf(
						rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
						rememberViewModelStoreNavEntryDecorator<NavKey>(),
					),
					sharedTransitionScope = this,
					transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
					popTransitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
					entryProvider = entryProvider {
						entry<Root> { Visible { shell(navigator) } }
						entry<Detail> { route -> Visible { DetailScreen(container, route, navigator) } }
						entry<Person> { route -> Visible { PersonScreen(container, route, navigator) } }
						entry<Extra> { route -> Visible { ExtraScreen(route.item, navigator) } }
					},
				)
			}
		}
		ToastHost(container.toasts, Modifier.align(Alignment.BottomCenter))
	}
}

/** Hands a page's animation scope to the shared elements inside it. */
@Composable
private fun Visible(content: @Composable () -> Unit) {
	CompositionLocalProvider(LocalEntryVisibility provides LocalNavAnimatedContentScope.current, content = content)
}

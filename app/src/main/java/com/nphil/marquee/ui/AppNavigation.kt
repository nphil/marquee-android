package com.nphil.marquee.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.nphil.marquee.downloads.DownloadEntry
import com.nphil.marquee.ui.components.ArtSpec
import com.nphil.marquee.ui.components.ToastHost
import com.nphil.marquee.ui.detail.DetailScreen
import com.nphil.marquee.ui.detail.ExtraScreen
import com.nphil.marquee.ui.detail.PersonScreen
import com.nphil.marquee.ui.live.LivePlayerDestination
import com.nphil.marquee.ui.live.RecordingsDestination
import com.nphil.marquee.ui.player.PlayerScreen
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

/**
 * A title playing full screen. [fromStart] plays from the beginning instead of resuming. [titleKey] files the audio and
 * subtitle choices: the show's key for an episode, and the title's own when null.
 */
@Serializable
data class Play(
	val ratingKey: String,
	val fromStart: Boolean = false,
	val titleKey: String? = null,
	val downloadId: String? = null,
	val recordingMediaID: Int? = null,
) : Route

/** A Plex channel; only the lineup key and the account scope are saved with the navigation stack. */
@Serializable
internal data class WatchLive(val scope: String, val channel: String) : Route

@Serializable
internal data class Recordings(val scope: String) : Route

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

	/** The art each poster or card on screen shows, by the title it opens: what that title's page draws until its own art has loaded. */
	private val arts = HashMap<String, ArtSpec>()

	internal fun registerArt(ratingKey: String, art: ArtSpec) {
		arts[ratingKey] = art
	}

	internal fun unregisterArt(ratingKey: String, art: ArtSpec) {
		if (arts[ratingKey] == art) arts.remove(ratingKey)
	}

	/** The art of the poster that opened [ratingKey]'s page, or null when the page was not opened from one. */
	internal fun originArt(ratingKey: String): ArtSpec? = if (origin == ratingKey) arts[ratingKey] else null

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

	/** Plays [item] full screen: where the server says it was left, or from the start. */
	fun openPlayer(item: PlexItem, fromStart: Boolean = false) =
		push(Play(item.ratingKey, fromStart, item.grandparentRatingKey ?: item.ratingKey))

	internal fun openDownload(entry: DownloadEntry) = push(Play(
		entry.title.ratingKey, titleKey = entry.title.showKey ?: entry.title.ratingKey, downloadId = entry.id,
	))

	internal fun openLive(scope: String, channel: String) = push(WatchLive(scope, channel))

	internal fun openRecordings(scope: String) = push(Recordings(scope))

	internal fun openRecording(ratingKey: String, titleKey: String, mediaID: Int) =
		push(Play(ratingKey, titleKey = titleKey, recordingMediaID = mediaID))

	/** A title the big screen chose: the phone's player opens it, and its picture goes to the display. */
	internal fun openPlay(ratingKey: String, titleKey: String, fromStart: Boolean) = push(Play(ratingKey, fromStart, titleKey))

	fun back() {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
	}

	/** Back to the tabs, whatever page is open above them. */
	internal fun toRoot() {
		while (stack.size > 1) stack.removeAt(stack.lastIndex)
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
 * tapped takes part, so it is safe on every poster of a screen. [art] is what the poster shows: the page draws it as
 * its hero until its own art has loaded, so the picture that was tapped is the one that flies.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.posterSharedElement(ratingKey: String, art: ArtSpec? = null): Modifier {
	val navigator = LocalNavigator.current ?: return this
	if (art != null) {
		DisposableEffect(navigator, ratingKey, art) {
			navigator.registerArt(ratingKey, art)
			onDispose { navigator.unregisterArt(ratingKey, art) }
		}
	}
	if (navigator.origin != ratingKey) return this
	// The poster stays whole under the hero as it fades in and goes once the hero covers it: both fading would dip to the canvas.
	return zoom(ratingKey, Corners(art?.radius ?: Radius.tile), enter = fadeIn(NavMotion.linear()), exit = ExitTransition.None)
}

/**
 * The hero of the page for [ratingKey], where the tapped poster's art lands. [from] is that art, when the page was
 * opened from a poster: the corners then run from the poster's to [radius] as the hero grows to [width].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.heroSharedElement(ratingKey: String, from: ArtSpec?, width: Dp, radius: Dp): Modifier {
	val corners = if (from == null) Corners(radius) else Corners(from.radius, radius, from.width, width)
	return zoom(ratingKey, corners, enter = fadeIn(NavMotion.linear()), exit = fadeOut(NavMotion.linear()))
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.zoom(ratingKey: String, corners: Corners, enter: EnterTransition, exit: ExitTransition): Modifier {
	val scope = LocalSharedTransitionScope.current ?: return this
	val visibility = LocalEntryVisibility.current ?: return this
	return with(scope) {
		this@zoom.sharedBounds(
			sharedContentState = rememberSharedContentState(PosterKey(ratingKey)),
			animatedVisibilityScope = visibility,
			enter = enter,
			exit = exit,
			boundsTransform = NavMotion.bounds,
			// The art is laid out afresh at each size, so a 2:3 poster becomes a 16:9 hero by cropping: scaled, the hero's
			// centre would show in the poster's place at the first frame.
			resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
			clipInOverlayDuringTransition = remember(corners) { CornerClip(corners) },
		)
	}
}

/** The corners of a shared container in flight: [fromRadius] at [fromWidth] wide, easing to [toRadius] at [toWidth]. One radius when no widths are given. */
@Immutable
private data class Corners(val fromRadius: Dp, val toRadius: Dp = fromRadius, val fromWidth: Dp = 0.dp, val toWidth: Dp = 0.dp)

@OptIn(ExperimentalSharedTransitionApi::class)
private class CornerClip(private val corners: Corners) : SharedTransitionScope.OverlayClip {
	private val path = Path()

	override fun getClipPath(
		sharedContentState: SharedTransitionScope.SharedContentState,
		bounds: Rect,
		layoutDirection: LayoutDirection,
		density: Density,
	): Path = with(density) {
		val from = corners.fromWidth.toPx()
		val span = corners.toWidth.toPx() - from
		val grown = if (span == 0f) 1f else ((bounds.width - from) / span).coerceIn(0f, 1f)
		val start = corners.fromRadius.toPx()
		path.reset()
		path.addRoundRect(RoundRect(bounds, CornerRadius(start + (corners.toRadius.toPx() - start) * grown)))
		path
	}
}

private val BackStack = SavedStateConfiguration {
	serializersModule = SerializersModule {
		polymorphic(NavKey::class) {
			subclass(Root::class)
			subclass(Detail::class)
			subclass(Person::class)
			subclass(Extra::class)
			subclass(Play::class)
			subclass(WatchLive::class)
			subclass(Recordings::class)
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
	LaunchedEffect(navigator, container) {
		container.glasses.plays.collect { navigator.openPlay(it.ratingKey, it.titleKey, it.fromStart) }
	}
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
					transitionSpec = { NavMotion.open },
					popTransitionSpec = { NavMotion.close },
					predictivePopTransitionSpec = { NavMotion.close },
					entryProvider = entryProvider {
						entry<Root> { Visible { shell(navigator) } }
						entry<Detail> { route -> Visible { DetailScreen(container, route, navigator) } }
						entry<Person> { route -> Visible { PersonScreen(container, route, navigator) } }
						entry<Extra> { route -> Visible { ExtraScreen(route.item, navigator) } }
						entry<Play> { route ->
							Visible {
								PlayerScreen(
									route.ratingKey, route.titleKey ?: route.ratingKey, route.fromStart,
									downloadId = route.downloadId, recordingMediaID = route.recordingMediaID, onClose = navigator::back,
								)
							}
						}
						entry<WatchLive> { route -> Visible { LivePlayerDestination(container, route, navigator::back) } }
						entry<Recordings> { route -> Visible { RecordingsDestination(container, route, navigator::back) } }
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

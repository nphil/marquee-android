package com.nphil.marquee.ui.detail

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.DeleteOutcome
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.Watchlist
import com.nphil.marquee.core.backdropPath
import com.nphil.marquee.core.formatChips
import com.nphil.marquee.core.logoPath
import com.nphil.marquee.core.metaLine
import com.nphil.marquee.core.minutesLeft
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.core.versionLabel
import com.nphil.marquee.playback.ThemeMusicEffect
import com.nphil.marquee.playback.TrailerPreview
import com.nphil.marquee.ui.Detail
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.components.ArtSpec
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.cardHeight
import com.nphil.marquee.ui.heroSharedElement
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** Wide enough for the hero to sit beside the title and actions, and for episodes to run in two columns. */
private val WideBreakpoint = 840.dp

/** The top bar below the status bar. */
private val BarHeight = Size.rowMin

/** A movie's, show's, season's or episode's page (DESIGN §8.3 and §8.4). */
@Composable
fun DetailScreen(container: AppContainer, route: Detail, navigator: Navigator) {
	val model = viewModel { DetailModel(container, route) }
	val state by model.state.collectAsStateWithLifecycle()
	val changes by container.changes.state.collectAsStateWithLifecycle()
	val item = state.item
	// A title deleted here, or from a page above this one, takes its page with it.
	androidx.compose.runtime.LaunchedEffect(item, changes.deleted) {
		if (item != null && changes.deleted.contains(item)) navigator.back()
	}
	val onTop = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
	val listState = rememberLazyListState()
	val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240 } }
	// A show's page, and each of its seasons', plays the show's theme tune; another page or the app leaving covers it.
	ThemeMusicEffect(item?.takeIf { it.type == MediaType.Show || it.type == MediaType.Season }, covered = !onTop)
	// The art of the poster that was tapped, read once: the hero draws it from the first frame and keeps it until its own has loaded.
	val carrier = remember { navigator.originArt(route.ratingKey) }
	Box(Modifier.fillMaxSize().background(lucent.canvas)) {
		val failure = state.failure
		if (item == null && failure != null) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				LoadFailure(failure, container.session.state.value.server?.name ?: "the server", model::retry)
			}
		} else {
			DetailPage(container, route.ratingKey, item, carrier, state, model, navigator, listState, onTop)
		}
		TopBar(if (scrolled) item?.title else null, scrolled, navigator::back)
	}
}

@Composable
internal fun LoadFailure(kind: CoreException.Kind, server: String, retry: () -> Unit) {
	val unreachable = kind == CoreException.Kind.Unreachable || kind == CoreException.Kind.NoConnections
	EmptyState(
		icon = if (unreachable) R.drawable.ic_wifi_off else R.drawable.ic_error,
		title = if (unreachable) "Can't reach $server" else "Couldn't open this",
		message = if (unreachable) "Check that it is on and this tablet is online." else "$server sent an answer Marquee did not expect.",
		action = { RetryButton("Retry", onClick = retry) },
	)
}

/** Static fills where the title, actions and synopsis will be, swapped for them atomically (DESIGN §11). The hero is not one: it is on screen from the first frame. */
@Composable
private fun HeaderSkeleton(modifier: Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		listOf(0.6f, 0.4f, 0.8f).forEach { fraction ->
			Box(Modifier.fillMaxWidth(fraction).height(Space.s5).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
		}
	}
}

/** The back button, over the page: clear at rest, glass with the title once the page scrolls under it. */
@Composable
internal fun TopBar(title: String?, scrolled: Boolean, onBack: () -> Unit) {
	val glass by animateColorAsState(if (scrolled) lucent.glass else Color.Transparent, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "bar")
	val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
	Row(
		modifier = Modifier.fillMaxWidth().background(glass).padding(top = top).height(BarHeight).padding(horizontal = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Box(
			modifier = Modifier
				.size(Size.target)
				.clip(CircleShape)
				.background(if (scrolled) Color.Transparent else lucent.glass)
				.clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
			contentAlignment = Alignment.Center,
		) {
			Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = lucent.ink)
		}
		if (title != null) {
			Text(title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

@Composable
private fun DetailPage(
	container: AppContainer,
	ratingKey: String,
	item: PlexItem?,
	carrier: ArtSpec?,
	state: DetailState,
	model: DetailModel,
	navigator: Navigator,
	listState: LazyListState,
	onTop: Boolean,
) {
	BoxWithConstraints(Modifier.fillMaxSize()) {
		val wide = maxWidth >= WideBreakpoint
		val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
		val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
		var version by rememberSaveable { mutableIntStateOf(0) }
		val versionIndex = version.coerceIn(0, maxOf(0, (item?.media?.size ?: 1) - 1))
		var tracksOpen by rememberSaveable { mutableStateOf(false) }
		var deleting by remember { mutableStateOf<DeleteTarget?>(null) }
		val trailer = remember(state.extras, item?.primaryExtraKey) { trailerOf(state.extras, item?.primaryExtraKey) }
		val heroPx = with(LocalDensity.current) { (maxWidth * 9 / 16).toPx() }
		val heroOnScreen by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < heroPx / 2 } }
		val context = LocalContext.current
		val actions = item?.let { page ->
			ItemActions(
				state = state,
				item = page,
				onWatched = { model.setWatched(page.ratingKey, !page.isWatched) },
				onWatchlist = model::toggleWatchlist,
				onTrailer = trailer?.let { extra -> { navigator.openExtra(extra) } },
				onTracks = { tracksOpen = true },
				onShare = { share(context, page) },
				onDeleteVersion = { deleting = DeleteTarget.Version(page, versionIndex) },
				onDelete = { deleting = DeleteTarget.Title(page) },
			)
		}
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(bottom = bottom + Space.s8 * 2),
		) {
			item(key = "header", contentType = "header") {
				if (wide) {
					WideHeader(ratingKey, item, carrier, state, navigator, actions, trailer, onTop && heroOnScreen, top, maxWidth, versionIndex, { version = it })
				} else {
					CompactHeader(ratingKey, item, carrier, navigator, actions, trailer, onTop && heroOnScreen, top, maxWidth)
				}
			}
			if (item != null) {
				if (!wide) {
					item(key = "details", contentType = "details") {
						DetailsBlock(item, state, versionIndex, { version = it }, { tracksOpen = true }, Modifier.padding(horizontal = Space.inset))
					}
				}
				seasonsSection(state, model, navigator, wide)
				castSection(item, navigator)
				extrasSection(state, navigator)
				relatedSection(state, navigator)
			}
		}
		if (tracksOpen) {
			item?.media?.getOrNull(versionIndex)?.parts?.firstOrNull()?.let { part ->
				TracksSheet(part, onDismiss = { tracksOpen = false })
			}
		}
		deleting?.let { target ->
			DeleteDialogs(
				target = target,
				onDismiss = { deleting = null },
				run = { done ->
					when (target) {
						is DeleteTarget.Title -> model.delete(target.item) { result ->
							if (result.outcome == DeleteOutcome.Deleted) {
								deleting = null
								navigator.back()
							}
							done(result.outcome)
						}
						is DeleteTarget.Version -> {
							val media = target.item.media[target.index]
							model.deleteVersion(target.item, media.id, media.versionLabel) { outcome ->
								if (outcome == DeleteOutcome.Deleted) deleting = null
								done(outcome)
							}
						}
					}
				},
			)
		}
	}
}

/** What the page's action row and More menu can do right now. */
private class ItemActions(
	val state: DetailState,
	val item: PlexItem,
	val onWatched: () -> Unit,
	val onWatchlist: () -> Unit,
	val onTrailer: (() -> Unit)?,
	val onTracks: () -> Unit,
	val onShare: () -> Unit,
	val onDeleteVersion: () -> Unit,
	val onDelete: () -> Unit,
)

@Composable
private fun CompactHeader(
	ratingKey: String,
	item: PlexItem?,
	carrier: ArtSpec?,
	navigator: Navigator,
	actions: ItemActions?,
	trailer: PlexItem?,
	trailerVisible: Boolean,
	top: Dp,
	width: Dp,
) {
	val height = cardHeight(width)
	Box(Modifier.fillMaxWidth()) {
		Column {
			StatusStrip(item?.backdropPath ?: item?.posterPath, carrier, width, height)
			Hero(ratingKey, item, carrier, trailer, trailerVisible, width, fade = true)
		}
		Column(Modifier.padding(top = top + height - Space.s7), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
			if (item != null && actions != null) {
				TitleBlock(item, navigator, Modifier.padding(horizontal = Space.inset))
				ActionRow(actions, Modifier.padding(horizontal = Space.inset))
			} else {
				HeaderSkeleton(Modifier.padding(horizontal = Space.inset))
			}
		}
	}
}

@Composable
private fun WideHeader(
	ratingKey: String,
	item: PlexItem?,
	carrier: ArtSpec?,
	state: DetailState,
	navigator: Navigator,
	actions: ItemActions?,
	trailer: PlexItem?,
	trailerVisible: Boolean,
	top: Dp,
	width: Dp,
	version: Int,
	onVersion: (Int) -> Unit,
) {
	val gap = Space.s6
	val heroWidth = (width - Space.inset * 2 - gap) * 0.56f
	Row(
		Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.inset, top = top + BarHeight, bottom = Space.s4),
		horizontalArrangement = Arrangement.spacedBy(gap),
	) {
		Hero(ratingKey, item, carrier, trailer, trailerVisible, heroWidth, fade = false)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
			if (item != null && actions != null) {
				TitleBlock(item, navigator, Modifier)
				ActionRow(actions, Modifier)
				DetailsBlock(item, state, version, onVersion, actions.onTracks, Modifier)
			} else {
				HeaderSkeleton(Modifier)
			}
		}
	}
}

/** The hero, and the art in it: where each is on screen frame by frame is what the open-flight test reads. */
const val HeroTag = "detail.hero"
const val HeroArtTag = "detail.hero.art"

/**
 * The 16:9 backdrop; the trailer plays over it once it has dwelt long enough, under the same fade into the canvas, so
 * the video blends into the page as the art does. It is on screen from the first frame, before the title has loaded,
 * so it can take the place of the poster that was tapped at once: it draws that poster's art ([carrier]) until its
 * own has loaded.
 */
@Composable
private fun Hero(ratingKey: String, item: PlexItem?, carrier: ArtSpec?, trailer: PlexItem?, trailerVisible: Boolean, width: Dp, fade: Boolean) {
	val height = cardHeight(width)
	val canvas = lucent.canvas
	val corner = if (fade) 0.dp else Radius.card
	Box(
		Modifier
			.testTag(HeroTag)
			.heroSharedElement(ratingKey, carrier, width, corner)
			.size(width, height)
			.clip(RoundedCornerShape(corner)),
	) {
		PlexArt(item?.backdropPath ?: item?.posterPath, width, height, Modifier.testTag(HeroArtTag), placeholder = carrier)
		if (trailer != null) TrailerPreview(trailer, trailerVisible, Modifier.matchParentSize())
		if (fade) {
			// The art melts into the canvas over its lower half, reaching 85% where the title starts.
			Box(
				Modifier.matchParentSize().background(
					Brush.verticalGradient(
						0.45f to Color.Transparent,
						0.60f to canvas.copy(alpha = 0.10f),
						0.75f to canvas.copy(alpha = 0.35f),
						0.88f to canvas.copy(alpha = 0.70f),
						0.95f to canvas.copy(alpha = 0.85f),
						1f to canvas,
					),
				),
			)
		}
	}
}

/** Above the hero the art runs on to the top edge as a blurred mirror of its top rows, so the page bleeds under the status bar. */
@Composable
private fun StatusStrip(art: String?, carrier: ArtSpec?, width: Dp, heroHeight: Dp) {
	val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
	if (top <= 0.dp || (art == null && carrier == null)) return
	val topPx = with(LocalDensity.current) { top.toPx() }
	Box(Modifier.fillMaxWidth().height(top).clipToBounds()) {
		PlexArt(
			art,
			width,
			heroHeight,
			Modifier
				.requiredSize(width, heroHeight)
				.graphicsLayer {
					transformOrigin = TransformOrigin(0.5f, topPx / 2f / size.height)
					scaleY = -1f
				}
				.blur(20.dp),
			placeholder = carrier,
		)
		Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(lucent.canvas.copy(alpha = 0.5f), Color.Transparent))))
	}
}

@Composable
private fun TitleBlock(item: PlexItem, navigator: Navigator, modifier: Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		val showKey = item.grandparentRatingKey
		if (item.type == MediaType.Episode && showKey != null && item.grandparentTitle != null) {
			Row(
				Modifier
					.heightIn(min = Size.target)
					.clickable(role = Role.Button, onClickLabel = "Open ${item.grandparentTitle}") { navigator.openItem(showKey) },
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(item.grandparentTitle, style = MaterialTheme.typography.titleMedium, color = lucent.accentText)
				Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = lucent.accentText, modifier = Modifier.size(20.dp))
			}
		}
		val logo = item.logoPath
		if (logo != null) {
			LogoArt(logo, item.title)
		} else {
			Text(
				item.title,
				style = MaterialTheme.typography.displaySmall,
				color = lucent.ink,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.semantics { heading() },
			)
		}
		val meta = item.metaLine
		val score = item.audienceRating ?: item.rating
		if (meta != null || score != null) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
				if (meta != null) Text(meta, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
				if (score != null) {
					if (meta != null) Spacer(Modifier.width(Space.s1))
					Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(16.dp))
					Text(ItemText.score(score), style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
				}
			}
		}
		val chips = item.media.firstOrNull()?.formatChips.orEmpty()
		if (chips.isNotEmpty()) {
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
				chips.forEach { chip ->
					Text(
						chip,
						style = MaterialTheme.typography.labelSmall,
						color = lucent.ink2,
						modifier = Modifier
							.border(1.dp, lucent.edge, RoundedCornerShape(Radius.control))
							.padding(horizontal = Space.s2, vertical = 2.dp),
					)
				}
			}
		}
		item.minutesLeft?.let { left ->
			Text(ItemText.left(left), style = MaterialTheme.typography.labelLarge, color = lucent.accentText)
		}
	}
}

@Composable
private fun ActionRow(actions: ItemActions, modifier: Modifier) {
	val item = actions.item
	val state = actions.state
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
		if (state.onServer) {
			RoundAction(
				icon = if (item.isWatched) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle,
				label = if (item.isWatched) "Mark unwatched" else "Mark watched",
				selected = item.isWatched,
				onClick = actions.onWatched,
			)
		}
		val listed = state.watchlisted
		if (listed != null) {
			RoundAction(
				icon = if (listed) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark,
				label = if (listed) "Remove from Watchlist" else "Add to Watchlist",
				selected = listed,
				onClick = actions.onWatchlist,
			)
		}
		MoreMenu(actions)
	}
}

@Composable
private fun MoreMenu(actions: ItemActions) {
	var open by remember { mutableStateOf(false) }
	val item = actions.item
	val state = actions.state
	val deletable = state.onServer && state.info.allowsMediaDeletion
	val hasTracks = item.media.firstOrNull()?.parts?.firstOrNull()?.tracks != null
	val shareable = Watchlist.shareUrl(item.guid) != null
	Box {
		RoundAction(R.drawable.ic_more_horiz, "More", selected = false, onClick = { open = true })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			@Composable
			fun entry(text: String, icon: Int, onClick: () -> Unit) = DropdownMenuItem(
				text = { Text(text) },
				leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
				onClick = {
					open = false
					onClick()
				},
			)
			actions.onTrailer?.let { entry("Play Trailer", R.drawable.ic_play_arrow, it) }
			if (hasTracks && state.onServer) entry("Audio & Subtitles", R.drawable.ic_closed_caption, actions.onTracks)
			if (shareable) entry("Share", R.drawable.ic_share, actions.onShare)
			if (deletable && item.media.size > 1 && (item.type == MediaType.Movie || item.type == MediaType.Episode)) {
				entry("Delete This Version…", R.drawable.ic_delete, actions.onDeleteVersion)
			}
			if (deletable) entry("Delete…", R.drawable.ic_delete, actions.onDelete)
		}
	}
}

/** A 48 dp round glyph button: on the sheet at rest, on the accent's container when it is on. */
@Composable
private fun RoundAction(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
	Box(
		modifier = Modifier
			.size(Size.target)
			.clip(CircleShape)
			.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else lucent.sheet)
			.border(1.dp, lucent.edge, CircleShape)
			.clickable(role = Role.Button, onClick = onClick)
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painterResource(icon),
			contentDescription = null,
			tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else lucent.ink,
			modifier = Modifier.size(24.dp),
		)
	}
}

/** The details under the actions: the version, the synopsis and the tracks (DESIGN §8.3 items 4 to 6). */
@Composable
private fun DetailsBlock(
	item: PlexItem,
	state: DetailState,
	version: Int,
	onVersion: (Int) -> Unit,
	onTracks: () -> Unit,
	modifier: Modifier,
) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		if (!state.onServer) {
			Text("Not on your servers", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
		}
		if (item.media.size > 1) VersionMenu(item, version, onVersion)
		if (item.summary.isNotBlank()) Synopsis(item.summary)
		if (state.onServer) TracksRow(item.media.getOrNull(version)?.parts?.firstOrNull(), onTracks)
	}
}

/** Where a title's own link is shared from. */
private fun share(context: Context, item: PlexItem) {
	val url = Watchlist.shareUrl(item.guid) ?: return
	val send = Intent(Intent.ACTION_SEND).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_SUBJECT, item.title)
		putExtra(Intent.EXTRA_TEXT, url)
	}
	context.startActivity(Intent.createChooser(send, null))
}

/** A title, or one version of it, to remove from the server's disk. */
private sealed interface DeleteTarget {
	val item: PlexItem

	data class Title(override val item: PlexItem) : DeleteTarget

	data class Version(override val item: PlexItem, val index: Int) : DeleteTarget
}

/**
 * Asks before deleting, as the iPhone does (Cancel is the way out), then deletes and says how it went: a turned-off
 * server setting can't be retried, a failed delete can.
 */
@Composable
private fun DeleteDialogs(target: DeleteTarget, onDismiss: () -> Unit, run: ((DeleteOutcome) -> Unit) -> Unit) {
	var failure by remember { mutableStateOf<DeleteOutcome?>(null) }
	var working by remember { mutableStateOf(false) }
	val item = target.item
	val result = failure
	if (result == null) {
		AlertDialog(
			onDismissRequest = { if (!working) onDismiss() },
			title = {
				Text(
					when (target) {
						is DeleteTarget.Title -> "Delete “${item.title}”?"
						is DeleteTarget.Version -> "Delete this version of “${item.title}”?"
					},
				)
			},
			text = {
				Text(
					when (target) {
						is DeleteTarget.Title -> when (item.type) {
							MediaType.Show -> "Every season and episode, and their files, are removed from the server's disk. This can't be undone."
							MediaType.Season -> "Every episode in this season, and their files, are removed from the server's disk. This can't be undone."
							else -> "Its files are removed from the server's disk. This can't be undone."
						}
						is DeleteTarget.Version ->
							"The ${item.media[target.index].versionLabel} file is removed from the server's disk. The other versions stay. This can't be undone."
					},
				)
			},
			confirmButton = {
				TextButton(
					enabled = !working,
					onClick = {
						working = true
						run { outcome ->
							working = false
							if (outcome != DeleteOutcome.Deleted) failure = outcome
						}
					},
					colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = lucent.danger),
				) { Text("Delete") }
			},
			dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
		)
	} else {
		val off = result == DeleteOutcome.NotAllowed
		AlertDialog(
			onDismissRequest = onDismiss,
			title = { Text(if (off) "Deleting Is Turned Off" else "Couldn't Delete") },
			text = {
				Text(
					if (off) {
						"The server doesn't allow deleting media. Its owner can turn on Allow media deletion in the Plex server's Settings, under Library."
					} else {
						"“${item.title}” is still on the server. Check the connection, then try again."
					},
				)
			},
			confirmButton = {
				if (off) {
					TextButton(onClick = onDismiss) { Text("OK") }
				} else {
					TextButton(onClick = { failure = null }) { Text("Retry") }
				}
			},
			dismissButton = if (off) null else ({ TextButton(onClick = onDismiss) { Text("Cancel") } }),
		)
	}
}

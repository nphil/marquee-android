package com.nphil.marquee.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.SeerrDetails
import com.nphil.marquee.core.SeerrKind
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.cardHeight
import com.nphil.marquee.ui.components.posterHeight
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

private val POSTER_PANE = 260.dp
private val COMPACT_POSTER = 112.dp

/** A title: its facts, where it stands with Seerr, and the way to request it. */
@Composable
internal fun TitleScreen(container: AppContainer, kind: SeerrKind, tmdbId: Int, nav: RequestsNav) {
	val model = viewModel { TitleModel(container.seerr, kind, tmdbId) }
	val state by model.state.collectAsStateWithLifecycle()
	var requesting by rememberSaveable { mutableStateOf(false) }
	Scaffold(
		topBar = {
			TopAppBar(
				title = {},
				navigationIcon = {
					IconButton(onClick = nav::back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
				},
			)
		},
		containerColor = lucent.canvas,
	) { padding ->
		BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).then(padding.sides())) {
			val wide = maxWidth >= WideBreakpoint
			when (val shown = state) {
				TitleModel.State.Loading -> TitleSkeleton(wide)
				is TitleModel.State.Failed -> Column(
					Modifier.fillMaxSize(),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.Center,
				) {
					EmptyState(
						icon = R.drawable.ic_cloud_off,
						title = "Can't load title",
						message = shown.message,
						action = { RetryButton("Retry", model::retry) },
					)
				}
				is TitleModel.State.Loaded -> {
					TitlePage(shown.details, wide, maxWidth, padding.calculateBottomPadding()) { requesting = true }
					if (requesting && shown.details.canRequestAny) {
						RequestSheet(
							details = shown.details,
							send = model::send,
							onSent = {
								requesting = false
								container.toasts.show("Requested")
							},
							onDismiss = { requesting = false },
						)
					}
				}
			}
		}
	}
}

@Composable
private fun TitlePage(details: SeerrDetails, wide: Boolean, windowWidth: Dp, bottom: Dp, onRequest: () -> Unit) {
	val title = details.title
	val meta = remember(details) { RequestsRules.metaLine(details) }
	// The art is asked of TMDB at the pixels it takes, so it needs the width it will be drawn at.
	val column = windowWidth - Space.inset * 2
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.inset),
		verticalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		if (wide) {
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
				TmdbArt(title.posterPath, POSTER_PANE, posterHeight(POSTER_PANE), Modifier.clip(RoundedCornerShape(Radius.tile)))
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
					Banner(title.backdropPath, minOf(column - POSTER_PANE - Space.s6, 640.dp))
					Header(details, meta)
					Actions(details, onRequest)
					Notes(details)
				}
			}
		} else {
			Banner(title.backdropPath, column)
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
				TmdbArt(title.posterPath, COMPACT_POSTER, posterHeight(COMPACT_POSTER), Modifier.clip(RoundedCornerShape(Radius.tile)))
				Column(Modifier.weight(1f)) { Header(details, meta) }
			}
			Actions(details, onRequest)
			Notes(details)
		}
		Box(Modifier.padding(bottom = bottom + Space.inset))
	}
}

/** The backdrop across the top, when TMDB has one. */
@Composable
private fun Banner(path: String?, width: Dp) {
	if (path == null) return
	TmdbArt(path, width, cardHeight(width), Modifier.clip(RoundedCornerShape(Radius.card)))
}

@Composable
private fun Header(details: SeerrDetails, meta: String) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		Text(
			details.title.name,
			style = MaterialTheme.typography.headlineMedium,
			color = lucent.ink,
			modifier = Modifier.semantics { heading() },
		)
		if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
		details.status?.let { StatusLine(it.badge, suffix = it.requestedBy?.let { name -> "Requested by $name" }) }
		details.status4K?.let { StatusLine(it.badge, prefix = "4K", suffix = it.requestedBy?.let { name -> "Requested by $name" }) }
	}
}

@Composable
private fun Actions(details: SeerrDetails, onRequest: () -> Unit) {
	val plex = details.plexItem
	val navigator = LocalNavigator.current
	val request = details.canRequestAny
	val open = plex != null && navigator != null
	if (!request && !open) return
	Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		if (request) {
			Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = Size.target)) {
				Text("Request", style = MaterialTheme.typography.labelLarge)
			}
		}
		if (plex != null && navigator != null) {
			FilledTonalButton(
				onClick = { navigator.openItem(plex.ratingKey) },
				modifier = Modifier.fillMaxWidth().heightIn(min = Size.target),
			) { Text("Open in Plex", style = MaterialTheme.typography.labelLarge) }
		}
	}
}

@Composable
private fun Notes(details: SeerrDetails) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		if (details.watchlistAddRequests) {
			Text("Adding this to the Watchlist requests it too.", style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		}
		details.tagline?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = lucent.ink2) }
		if (details.title.overview.isNotBlank()) Text(details.title.overview, style = MaterialTheme.typography.bodyLarge, color = lucent.ink)
	}
}

@Composable
private fun TitleSkeleton(wide: Boolean) {
	val shape = RoundedCornerShape(Radius.tile)
	Row(
		modifier = Modifier.padding(Space.inset).semantics { contentDescription = "Loading" },
		horizontalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		val width = if (wide) POSTER_PANE else COMPACT_POSTER
		Box(Modifier.size(width, posterHeight(width)).clip(shape).background(lucent.trackOff))
		Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
			Box(Modifier.size(220.dp, 28.dp).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
			Box(Modifier.size(140.dp, 18.dp).clip(RoundedCornerShape(Radius.control)).background(lucent.trackOff))
		}
	}
}

// MARK: Request sheet

/**
 * What to request: the quality, and for a series the seasons. A refusal stays in the sheet in words, and an
 * "already requested" one ends the asking; the sheet cannot be dismissed while the request is on its way.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RequestSheet(
	details: SeerrDetails,
	send: suspend (is4K: Boolean, seasons: Set<Int>) -> TitleModel.Refusal?,
	onSent: () -> Unit,
	onDismiss: () -> Unit,
) {
	var is4K by remember { mutableStateOf(RequestsRules.starts4K(details)) }
	var chosen by remember { mutableStateOf(RequestsRules.startingSeasons(details, is4K)) }
	var sending by remember { mutableStateOf(false) }
	var refusal by remember { mutableStateOf<TitleModel.Refusal?>(null) }
	val scope = rememberCoroutineScope()
	val tv = details.title.kind == SeerrKind.Tv
	val canSend = !sending && refusal?.already != true && RequestsRules.canSend(details, is4K, chosen)
	ModalBottomSheet(
		onDismissRequest = { if (!sending) onDismiss() },
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !sending }),
		containerColor = lucent.canvas,
	) {
		Column(
			modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.inset).padding(bottom = Space.s6),
			verticalArrangement = Arrangement.spacedBy(GroupGap),
		) {
			Text(
				"Request ${details.title.name}",
				style = MaterialTheme.typography.titleLarge,
				color = lucent.ink,
				modifier = Modifier.padding(bottom = Space.s3).semantics { heading() },
			)
			if (details.canRequest4K) {
				ToggleRow("Request in 4K", is4K, enabled = details.canRequest && !sending) { on ->
					is4K = on
					chosen = RequestsRules.startingSeasons(details, on)
					refusal = null
				}
			}
			if (tv) {
				Text(
					"Seasons",
					style = MaterialTheme.typography.labelLarge,
					color = lucent.ink2,
					modifier = Modifier.padding(start = Space.s4, top = Space.s4, bottom = Space.s2),
				)
				val seasons = details.seasonChoices(is4K)
				if (details.partialRequests) {
					seasons.forEachIndexed { index, season ->
						ToggleRow(
							label = season.name,
							detail = season.badge?.title,
							checked = season.number in chosen,
							enabled = season.requestable && !sending,
							index = index,
							count = seasons.size,
						) { on -> chosen = if (on) chosen + season.number else chosen - season.number }
					}
				} else {
					Text(
						"All available seasons will be requested.",
						style = MaterialTheme.typography.bodyMedium,
						color = lucent.ink2,
						modifier = Modifier.padding(horizontal = Space.s4),
					)
				}
			}
			refusal?.let { ErrorLine(it.message, Modifier.padding(horizontal = Space.s4, vertical = Space.s3)) }
			Button(
				onClick = {
					sending = true
					refusal = null
					scope.launch {
						val result = send(is4K, chosen)
						sending = false
						if (result == null) onSent() else refusal = result
					}
				},
				enabled = canSend,
				modifier = Modifier.padding(top = Space.s4).fillMaxWidth().heightIn(min = Size.target),
			) {
				if (sending) LoadingIndicator(Modifier.size(28.dp)) else Text("Request", style = MaterialTheme.typography.labelLarge)
			}
		}
	}
}

@Composable
private fun ToggleRow(
	label: String,
	checked: Boolean,
	enabled: Boolean,
	index: Int = 0,
	count: Int = 1,
	detail: String? = null,
	onChange: (Boolean) -> Unit,
) {
	GroupRow(index, count, onClick = if (enabled) ({ onChange(!checked) }) else null) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) lucent.ink else lucent.ink3)
			detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2) }
		}
		Switch(checked = checked, onCheckedChange = null, enabled = enabled)
	}
}

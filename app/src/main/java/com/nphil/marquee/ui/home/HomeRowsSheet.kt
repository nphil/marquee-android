package com.nphil.marquee.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.HomeRowsModel
import com.nphil.marquee.browse.HomeRowsUi
import com.nphil.marquee.core.HomeRow
import com.nphil.marquee.core.posterPath
import com.nphil.marquee.core.posterTitle
import com.nphil.marquee.ui.browse.RemoteContent
import com.nphil.marquee.ui.browse.RowsSkeleton
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.ReorderState
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.components.reorderContainer
import com.nphil.marquee.ui.components.reorderHandle
import com.nphil.marquee.ui.components.reorderRow
import com.nphil.marquee.ui.components.rememberReorderState
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

private val RowHeight = 72.dp

/**
 * The Home Rows editor (DESIGN §8.1) as a sheet over Home: a switch and a drag handle on every row the server offers,
 * saved at once. Library rows appear once their switch is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRowsSheet(env: BrowseEnvironment, place: BrowsePlace, onSignIn: () -> Unit, onDismiss: () -> Unit) {
	val model: HomeRowsModel = viewModel(key = "home.rows:${place.key}") { HomeRowsModel(env, place) }
	LaunchedEffect(model) { model.load() }
	val ui by model.ui.collectAsStateWithLifecycle()
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		containerColor = lucent.sheet,
		contentColor = lucent.ink,
	) {
		HomeRowsContent(
			ui = ui,
			profile = place.profileName,
			serverName = place.server.name,
			onShown = model::setShown,
			onMove = model::move,
			onReset = model::reset,
			onRetry = model::load,
			onSignIn = onSignIn,
			onDone = onDismiss,
		)
	}
}

@Composable
internal fun HomeRowsContent(
	ui: HomeRowsUi,
	profile: String?,
	serverName: String,
	onShown: (HomeRow, Boolean) -> Unit,
	onMove: (from: Int, to: Int) -> Unit,
	onReset: () -> Unit,
	onRetry: () -> Unit,
	onSignIn: () -> Unit,
	onDone: () -> Unit,
) {
	Column(Modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().padding(start = Space.inset, end = Space.s2),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text("Home Rows", style = MaterialTheme.typography.titleLarge, color = lucent.ink, modifier = Modifier.weight(1f))
			TextAction("Done", onDone)
		}
		RemoteContent(
			remote = ui.remote,
			serverName = serverName,
			onRetry = onRetry,
			onSignIn = onSignIn,
			modifier = Modifier.weight(1f),
			skeleton = { RowsSkeleton(Modifier.padding(top = Space.s3)) },
		) {
			if (ui.rows.isEmpty()) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					EmptyState(R.drawable.ic_video_library, "No rows yet", "The server offers nothing for Home.")
				}
			} else {
				RowList(ui, profile, onShown, onMove, onReset)
			}
		}
	}
}

@Composable
private fun RowList(
	ui: HomeRowsUi,
	profile: String?,
	onShown: (HomeRow, Boolean) -> Unit,
	onMove: (from: Int, to: Int) -> Unit,
	onReset: () -> Unit,
) {
	val scroll = rememberScrollState()
	val reorder = rememberReorderState(scroll, RowHeight, ui.rows.size, onMove)
	BoxWithConstraints(Modifier.fillMaxSize()) {
		val compact = maxWidth < 480.dp
		Column(Modifier.fillMaxSize().reorderContainer(reorder).verticalScroll(scroll)) {
			ui.rows.forEachIndexed { index, row ->
				key(row.id) {
					RowItem(row, index, ui.rows.size, compact, reorder, onShown = { onShown(row, it) }, onMove = onMove)
				}
			}
			Footer("Drag rows into the order Home shows them. A library's rows appear once switched on.")
			TextButton(
				onClick = onReset,
				enabled = !ui.isDefault,
				modifier = Modifier.padding(horizontal = Space.s2),
			) { Text("Reset to Default") }
			Footer("Saved for ${profile ?: "this profile"}. Reset brings back the server's rows in its order.")
			Spacer(Modifier.height(Space.s6))
		}
	}
}

@Composable
private fun Footer(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = lucent.ink3,
		modifier = Modifier.padding(horizontal = Space.inset, vertical = Space.s1),
	)
}

@Composable
private fun RowItem(
	row: HomeRow,
	index: Int,
	count: Int,
	compact: Boolean,
	reorder: ReorderState,
	onShown: (Boolean) -> Unit,
	onMove: (from: Int, to: Int) -> Unit,
) {
	val caption = remember(row.hub.items) {
		row.hub.items.take(3).joinToString(", ") { it.posterTitle }.ifEmpty { "Empty right now" }
	}
	Row(
		Modifier
			.fillMaxWidth()
			.height(RowHeight)
			.reorderRow(reorder, index)
			.background(lucent.sheet)
			.semantics {
				customActions = listOfNotNull(
					if (index > 0) CustomAccessibilityAction("Move Up") { onMove(index, index - 1); true } else null,
					if (index < count - 1) CustomAccessibilityAction("Move Down") { onMove(index, index + 1); true } else null,
				)
			},
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			Modifier.padding(start = Space.s2).size(Size.target).reorderHandle(reorder, index),
			contentAlignment = Alignment.Center,
		) {
			Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = "Reorder ${row.title}", tint = lucent.ink3)
		}
		RowPreview(row, compact, Modifier.alpha(if (row.shown) 1f else 0.4f))
		Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
			Text(row.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(caption, style = MaterialTheme.typography.bodySmall, color = lucent.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Switch(checked = row.shown, onCheckedChange = onShown)
		Spacer(Modifier.width(Space.inset))
	}
}

/** The first titles of a row, fanned out small: three posters, or the first card of Continue Watching. */
@Composable
private fun RowPreview(row: HomeRow, compact: Boolean, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(Radius.control / 2)
	val items = row.hub.items
	val posterWidth = if (compact) 24.dp else 32.dp
	val posterHeight = if (compact) 36.dp else 48.dp
	val cardWidth = if (compact) 48.dp else 64.dp
	val cardHeight = if (compact) 27.dp else 36.dp
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
		when {
			items.isEmpty() -> Box(
				Modifier.size(posterWidth * 3 + 4.dp, posterHeight).clip(shape).background(lucent.trackOff),
				contentAlignment = Alignment.Center,
			) {
				Icon(painterResource(R.drawable.ic_video_library), contentDescription = null, tint = lucent.ink3, modifier = Modifier.size(16.dp))
			}
			row.continueWatching -> Box(Modifier.size(posterWidth * 3 + 4.dp, posterHeight), contentAlignment = Alignment.Center) {
				PlexArt(items.first().cardArtPath, cardWidth, cardHeight, Modifier.clip(shape))
			}
			else -> items.take(3).forEach { PlexArt(it.posterPath, posterWidth, posterHeight, Modifier.clip(shape)) }
		}
	}
}

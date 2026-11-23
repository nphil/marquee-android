package com.nphil.marquee.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.TabBarModel
import com.nphil.marquee.browse.TabBarUi
import com.nphil.marquee.browse.movable
import com.nphil.marquee.browse.tabNote
import com.nphil.marquee.container
import com.nphil.marquee.core.Destination
import com.nphil.marquee.core.TabBarBehavior
import com.nphil.marquee.core.TabLayout
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.ReorderState
import com.nphil.marquee.ui.components.rememberReorderState
import com.nphil.marquee.ui.components.reorderContainer
import com.nphil.marquee.ui.components.reorderHandle
import com.nphil.marquee.ui.components.reorderRow
import com.nphil.marquee.ui.shell.icon
import com.nphil.marquee.ui.shell.tabLimit
import com.nphil.marquee.ui.shell.title
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.components.pageScroll
import com.nphil.marquee.ui.theme.lucent

/** Room for a title and a note of two lines; the reorder needs every row of one height. */
private val RowHeight = 88.dp

/**
 * Settings › Tab Bar (DESIGN §7, §8.7): which places are tabs, dragged into the bar's order, and whether the phone's
 * bar hides while a page scrolls. Each place names its way in for when it is not a tab. A change applies at once. A
 * phone's bar holds as many tabs as fit its width; a tablet's sidebar shows them all.
 */
@Composable
fun TabBarPage(contentPadding: PaddingValues) {
	val container = LocalContext.current.container
	val model = viewModel { TabBarModel(container.settings, container.seerr.enabled) }
	val ui by model.ui.collectAsStateWithLifecycle()
	val limit = tabLimit(LocalConfiguration.current.screenWidthDp.toFloat())
	val current = ui
	if (current == null) {
		Box(Modifier.fillMaxSize())
		return
	}
	val rows = current.layout.movable()
	val scroll = rememberScrollState()
	val reorder = rememberReorderState(scroll, RowHeight, rows.size, model::move)
	Column(
		Modifier
			.fillMaxSize()
			.reorderContainer(reorder)
			.pageScroll(scroll, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
			.verticalScroll(scroll)
			.padding(contentPadding),
	) {
		Heading("Tabs")
		rows.forEachIndexed { index, destination ->
			TabRow(
				destination = destination,
				ui = current,
				limit = limit,
				index = index,
				count = rows.size,
				reorder = reorder,
				onTab = { model.setTab(destination, it, limit) },
				onMove = model::move,
			)
		}
		TabRow(Destination.Search, current, limit, index = null, count = rows.size, reorder = reorder, onTab = { model.setTab(Destination.Search, it, limit) })
		SettingsFootnote(tabsNote(limit), Modifier.padding(top = Space.s2))

		Heading("On Scroll")
		TabBarBehavior.entries.forEachIndexed { index, behavior ->
			GroupRow(
				index = index,
				count = TabBarBehavior.entries.size,
				modifier = Modifier.semantics {
					role = Role.RadioButton
					selected = current.behavior == behavior
				},
				onClick = { model.setBehavior(behavior) },
			) {
				Text(behavior.label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
				if (current.behavior == behavior) {
					Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.accentText)
				}
			}
		}
		SettingsFootnote(
			"Hide on scroll slides the phone's bar away while a page scrolls down and brings it back on the way up. The tablet's sidebar always stays.",
			Modifier.padding(top = Space.s2),
		)
	}
}

private val TabBarBehavior.label: String
	get() = when (this) {
		TabBarBehavior.Always -> "Always show"
		TabBarBehavior.HideOnScroll -> "Hide on scroll"
	}

private fun tabsNote(limit: Int): String {
	val fixed = "Home stays a tab and Search stays last."
	if (limit == TabLayout.NO_LIMIT) return "In this order, all of them in the sidebar. $fixed"
	return "In this order, up to $limit in the bar at this width. A tablet's sidebar shows them all. $fixed"
}

/**
 * One destination's switch. A movable one ([index] set) has a drag handle and Move Up and Move Down actions; Search
 * ([index] null) stays below them, last.
 */
@Composable
private fun TabRow(
	destination: Destination,
	ui: TabBarUi,
	limit: Int,
	index: Int?,
	count: Int,
	reorder: ReorderState,
	onTab: (Boolean) -> Unit,
	onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
	val available = destination in ui.available
	val held = index != null && reorder.from == index
	val top = if (index == 0) Radius.card else 0.dp
	val bottom = if (index == null) Radius.card else 0.dp
	val shape = if (held) RoundedCornerShape(Radius.card) else RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
	val edge = lucent.edge
	var modifier = Modifier.fillMaxWidth().height(RowHeight)
	if (index != null) modifier = modifier.reorderRow(reorder, index)
	Row(
		modifier
			.background(lucent.sheet, shape)
			.drawBehind {
				// A hairline between rows, inset past the handle; the last row ends the card.
				if (index != null && !held) drawLine(edge, Offset(Size.target.toPx(), size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
			}
			.semantics {
				if (index != null) {
					customActions = listOfNotNull(
						if (index > 0) CustomAccessibilityAction("Move Up") { onMove(index, index - 1); true } else null,
						if (index < count - 1) CustomAccessibilityAction("Move Down") { onMove(index, index + 1); true } else null,
					)
				}
			},
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (index != null) {
			Box(Modifier.padding(start = Space.s2).size(Size.target).reorderHandle(reorder, index), contentAlignment = Alignment.Center) {
				Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = "Reorder ${destination.title}", tint = lucent.ink3)
			}
		} else {
			Spacer(Modifier.size(Size.target + Space.s2))
		}
		Icon(painterResource(destination.icon), contentDescription = null, tint = lucent.accentText, modifier = Modifier.padding(start = Space.s2).size(24.dp))
		Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
			Text(destination.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				tabNote(destination, available),
				style = MaterialTheme.typography.bodySmall,
				color = lucent.ink2,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Switch(
			checked = destination in ui.tabs(limit),
			onCheckedChange = onTab,
			enabled = ui.layout.canSwitch(destination, ui.available, limit),
		)
		Spacer(Modifier.size(Space.s4))
	}
}

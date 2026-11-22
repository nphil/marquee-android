package com.nphil.marquee.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.pageScroll
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode
import com.nphil.marquee.ui.theme.Themes
import com.nphil.marquee.ui.theme.lucent

/**
 * The theme picker (DESIGN §8.7): matching the system or not, then a grid of themes for dark and for
 * light, Wallpaper first where the tablet has it. A choice applies at once.
 */
@Composable
fun AppearancePage(
	themes: Themes,
	choice: ThemeChoice,
	onTheme: ((ThemeChoice) -> ThemeChoice) -> Unit,
	contentPadding: PaddingValues,
) {
	val systemDark = isSystemInDarkTheme()
	var shownMode by rememberSaveable { mutableStateOf<ThemeMode?>(null) }
	val mode = shownMode ?: choice.mode(systemDark)
	val tiles = remember(mode) { themes.choices(mode) }
	val grid = rememberLazyGridState()
	LazyVerticalGrid(
		columns = GridCells.Adaptive(Size.gridMin),
		modifier = Modifier.fillMaxSize().pageScroll(grid, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
		state = grid,
		contentPadding = contentPadding,
		horizontalArrangement = Arrangement.spacedBy(Space.s4),
		verticalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		item(key = "match", span = { GridItemSpan(maxLineSpan) }) {
			MatchSystem(choice.matchSystem) { on -> onTheme { it.matching(on, systemDark) } }
		}
		item(key = "modes", span = { GridItemSpan(maxLineSpan) }) {
			ModeButtons(mode) { shownMode = it }
		}
		items(tiles, key = { it.id }) { theme ->
			ThemeTile(theme, selected = choice.isSelected(theme.id, mode)) { onTheme { it.select(theme.id, mode) } }
		}
	}
}

@Composable
private fun MatchSystem(on: Boolean, onChange: (Boolean) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		GroupRow(index = 0, count = 1, onClick = { onChange(!on) }) {
			Text(
				"Match System Appearance",
				style = MaterialTheme.typography.titleMedium,
				color = lucent.ink,
				modifier = Modifier.weight(1f),
			)
			Switch(checked = on, onCheckedChange = null)
		}
		Text(
			if (on) "A theme for light and one for dark, following the tablet." else "One theme in both appearances.",
			style = MaterialTheme.typography.bodySmall,
			color = lucent.ink2,
			modifier = Modifier.padding(horizontal = Space.s4),
		)
	}
}

/** Dark and Light as a connected button group: the grid below shows that appearance's themes. */
@Composable
private fun ModeButtons(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
	) {
		listOf(ThemeMode.Dark, ThemeMode.Light).forEachIndexed { index, option ->
			ToggleButton(
				checked = mode == option,
				onCheckedChange = { onMode(option) },
				shapes = if (index == 0) {
					ButtonGroupDefaults.connectedLeadingButtonShapes()
				} else {
					ButtonGroupDefaults.connectedTrailingButtonShapes()
				},
				modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
			) {
				Text(if (option == ThemeMode.Dark) "Dark" else "Light")
			}
		}
	}
}

/** A theme drawn as a small screen in its own colours, with its name; the chosen one is ringed. */
@Composable
private fun ThemeTile(theme: LucentTheme, selected: Boolean, onClick: () -> Unit) {
	val ring = lucent.focus
	val shape = RoundedCornerShape(Radius.tile)
	Column(
		modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Box(
			Modifier
				.fillMaxWidth()
				.aspectRatio(3f / 4f)
				.drawBehind {
					if (selected) {
						// 3 dp ring, 4 dp outside the tile.
						val outset = 4.dp.toPx() + 1.5.dp.toPx()
						drawRoundRect(
							ring,
							topLeft = Offset(-outset, -outset),
							size = GeometrySize(size.width + 2 * outset, size.height + 2 * outset),
							cornerRadius = CornerRadius(Radius.tile.toPx() + outset),
							style = Stroke(3.dp.toPx()),
						)
					}
				},
		) {
			ThemePreview(theme, Modifier.fillMaxSize().clip(shape).border(1.dp, theme.edge, shape))
			if (selected) {
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.padding(Space.s2)
						.size(24.dp)
						.background(lucent.accent, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.accentInk, modifier = Modifier.size(16.dp))
				}
			}
		}
		Text(
			theme.name,
			style = MaterialTheme.typography.labelLarge,
			color = if (selected) lucent.ink else lucent.ink2,
			textAlign = TextAlign.Center,
			maxLines = 2,
		)
	}
}

/** Title and subtitle in ink and ink2, an accent pill, and three posters on a sheet. */
@Composable
private fun ThemePreview(theme: LucentTheme, modifier: Modifier) {
	Spacer(
		modifier.drawWithCache {
			val w = size.width
			val h = size.height
			val pad = w * 0.1f
			val line = h * 0.05f
			val sheet = theme.sheet.compositeOver(theme.canvas)
			val poster = theme.trackOff.compositeOver(sheet)
			val sheetTop = h * 0.5f
			val gap = w * 0.05f
			val posterWidth = (w - pad * 2 - gap * 2) / 3
			onDrawBehind {
				drawRect(theme.canvas)
				drawRoundRect(theme.ink, Offset(pad, pad), GeometrySize(w * 0.55f, line), CornerRadius(line / 2))
				drawRoundRect(theme.ink2, Offset(pad, pad + line * 1.8f), GeometrySize(w * 0.4f, line), CornerRadius(line / 2))
				drawRoundRect(theme.accent, Offset(pad, pad + line * 4f), GeometrySize(w * 0.36f, line * 1.8f), CornerRadius(line * 0.9f))
				drawRoundRect(sheet, Offset(pad / 2, sheetTop), GeometrySize(w - pad, h - sheetTop - pad / 2), CornerRadius(pad))
				repeat(3) { i ->
					drawRoundRect(
						poster,
						Offset(pad + i * (posterWidth + gap), sheetTop + pad / 1.5f),
						GeometrySize(posterWidth, posterWidth * 1.5f),
						CornerRadius(pad / 3),
					)
				}
			}
		},
	)
}

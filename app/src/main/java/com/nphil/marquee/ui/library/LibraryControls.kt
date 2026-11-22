package com.nphil.marquee.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.browse.LibraryChip
import com.nphil.marquee.browse.LibraryOptions
import com.nphil.marquee.browse.LibrarySegment
import com.nphil.marquee.core.LibrarySection
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.SectionQuery.Sort
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** The tags of the Library's controls, for tests that drive them. */
object LibraryTags {
	const val ViewMenu = "library.viewMenu"
	const val Grid = "library.grid"
}

/** A menu in the Library's own look: the same sheet and edge as a long press's menu. */
@Composable
private fun LibraryMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
	DropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismiss,
		shape = RoundedCornerShape(Radius.row),
		containerColor = lucent.sheet,
		tonalElevation = 0.dp,
		border = BorderStroke(1.dp, lucent.edge),
		content = content,
	)
}

/** One choice of a menu, with a check when it is the one chosen. */
@Composable
private fun MenuChoice(text: String, chosen: Boolean, onClick: () -> Unit, @DrawableRes icon: Int? = null) {
	DropdownMenuItem(
		text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = lucent.ink) },
		onClick = onClick,
		modifier = Modifier.semantics { selected = chosen },
		leadingIcon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = lucent.ink2) } },
		trailingIcon = if (chosen) {
			{ Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.accentText) }
		} else {
			null
		},
	)
}

/** The Library's name in the app bar; with more than one library it opens a menu of them. */
@Composable
internal fun LibraryTitle(title: String, libraries: List<LibrarySection>, chosen: String?, onChoose: (String) -> Unit) {
	if (libraries.size < 2) {
		Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
		return
	}
	var open by remember { mutableStateOf(false) }
	Box {
		Row(
			modifier = Modifier
				.clip(RoundedCornerShape(Radius.control))
				.clickable(role = Role.Button, onClickLabel = "Choose library") { open = true }
				.heightIn(min = Size.target)
				.padding(horizontal = Space.s2)
				.semantics { heading() },
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s1),
		) {
			Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = lucent.ink2)
		}
		LibraryMenu(open, { open = false }) {
			for (library in libraries) {
				MenuChoice(
					text = library.title,
					chosen = library.key == chosen,
					icon = if (library.itemType == MediaType.Show) R.drawable.ic_tv else R.drawable.ic_movie,
					onClick = {
						open = false
						onChoose(library.key)
					},
				)
			}
		}
	}
}

private enum class ViewPage { Closed, Main, Genres }

@DrawableRes
private fun Sort.icon(): Int = when (this) {
	Sort.Title -> R.drawable.ic_match_case
	Sort.RecentlyAdded -> R.drawable.ic_calendar_add_on
	Sort.ReleaseDate -> R.drawable.ic_calendar_month
	Sort.Rating -> R.drawable.ic_star
	Sort.AudienceRating -> R.drawable.ic_group
	Sort.RecentlyWatched -> R.drawable.ic_schedule
	Sort.Plays -> R.drawable.ic_play_circle
	Sort.Random -> R.drawable.ic_shuffle
}

/** The View button of the app bar and its menu: sort field, order, genre, and Unwatched Only. */
@Composable
internal fun ViewMenuButton(
	options: LibraryOptions,
	genres: List<PlexDirectory>,
	onSort: (Sort) -> Unit,
	onOrder: (reversed: Boolean) -> Unit,
	onGenre: (PlexDirectory?) -> Unit,
	onUnwatched: (Boolean) -> Unit,
) {
	var page by remember { mutableStateOf(ViewPage.Closed) }
	val close = { page = ViewPage.Closed }
	Box {
		IconButton(onClick = { page = ViewPage.Main }, modifier = Modifier.testTag(LibraryTags.ViewMenu)) {
			Icon(painterResource(R.drawable.ic_filter_list), contentDescription = "View")
		}
		LibraryMenu(page == ViewPage.Main, close) {
			for (field in Sort.entries) {
				MenuChoice(field.title, chosen = field == options.sort, icon = field.icon(), onClick = {
					close()
					onSort(field)
				})
			}
			val orders = options.sort.orders
			if (orders != null) {
				HorizontalDivider(color = lucent.edge)
				MenuChoice(orders.first, chosen = !options.reversed, icon = R.drawable.ic_arrow_downward, onClick = {
					close()
					onOrder(false)
				})
				MenuChoice(orders.reversed, chosen = options.reversed, icon = R.drawable.ic_arrow_upward, onClick = {
					close()
					onOrder(true)
				})
			}
			HorizontalDivider(color = lucent.edge)
			if (genres.isNotEmpty()) {
				DropdownMenuItem(
					text = {
						Text(
							options.genre?.let { "Genre: ${it.title}" } ?: "Genre",
							style = MaterialTheme.typography.bodyLarge,
							color = lucent.ink,
						)
					},
					onClick = { page = ViewPage.Genres },
					leadingIcon = { Icon(painterResource(R.drawable.ic_theater_comedy), contentDescription = null, tint = lucent.ink2) },
					trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = lucent.ink2) },
				)
			}
			MenuChoice("Unwatched Only", chosen = options.unwatchedOnly, icon = R.drawable.ic_visibility_off, onClick = {
				close()
				onUnwatched(!options.unwatchedOnly)
			})
		}
		LibraryMenu(page == ViewPage.Genres, close) {
			DropdownMenuItem(
				text = { Text("Genre", style = MaterialTheme.typography.titleSmall, color = lucent.ink2) },
				onClick = { page = ViewPage.Main },
				leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = lucent.ink2) },
			)
			HorizontalDivider(color = lucent.edge)
			MenuChoice("All Genres", chosen = options.genre == null, onClick = {
				close()
				onGenre(null)
			})
			for (genre in genres) {
				MenuChoice(genre.title, chosen = genre == options.genre, onClick = {
					close()
					onGenre(genre)
				})
			}
		}
	}
}

/** All, Collections and Recommended as one choice of three. */
@Composable
internal fun SegmentControl(selected: LibrarySegment, onSelect: (LibrarySegment) -> Unit, modifier: Modifier = Modifier) {
	val segments = LibrarySegment.entries
	SingleChoiceSegmentedButtonRow(modifier) {
		segments.forEachIndexed { index, segment ->
			SegmentedButton(
				selected = segment == selected,
				onClick = { onSelect(segment) },
				shape = SegmentedButtonDefaults.itemShape(index, segments.size),
				modifier = Modifier.heightIn(min = Size.target),
				icon = {},
				label = { Text(segment.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			)
		}
	}
}

/** The filters in force as chips; a tap on one removes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterChips(chips: List<LibraryChip>, onRemove: (LibraryChip) -> Unit, modifier: Modifier = Modifier) {
	FlowRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		for (chip in chips) {
			InputChip(
				selected = true,
				onClick = { onRemove(chip) },
				label = { Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
				trailingIcon = {
					Icon(
						painterResource(R.drawable.ic_close),
						contentDescription = "Remove filter",
						modifier = Modifier.size(InputChipDefaults.IconSize),
					)
				},
			)
		}
	}
}

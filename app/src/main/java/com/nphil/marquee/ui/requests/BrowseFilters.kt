package com.nphil.marquee.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.SeerrBrowseQuery
import com.nphil.marquee.core.SeerrCatalogue
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** One choice of a picker: [id] null is the "Any" choice. */
private data class Option(val id: String?, val label: String)

/**
 * The View menu: how the list is sorted and which titles it keeps. It is a sheet on a narrow window and a panel
 * beside the list on a wide one; the same filters either way, and a change lists again from the start.
 */
@Composable
internal fun BrowseFilters(
	query: SeerrBrowseQuery,
	catalogue: SeerrCatalogue,
	options: FilterOptions,
	region: String,
	onEdit: ((SeerrBrowseQuery) -> SeerrBrowseQuery) -> Unit,
	onRegion: (String) -> Unit,
	onStreaming: (provider: Int?, region: String) -> Unit,
	onClear: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val sort = catalogue.sorts.firstOrNull { it.id == query.sort }
	Column(modifier.padding(horizontal = Space.inset), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		Text("View", style = MaterialTheme.typography.titleLarge, color = lucent.ink, modifier = Modifier.padding(bottom = Space.s2))

		PickerRow(0, 2, "Sort By", sort?.title ?: query.sort, catalogue.sorts.map { Option(it.id, it.title) }) { id ->
			if (id != null) onEdit { it.copy(sort = id) }
		}
		OrderRow(1, 2, sort, query.reversed) { reversed -> onEdit { it.copy(reversed = reversed) } }
		Spacer()

		PickerRow(
			0, 4, "Decade",
			catalogue.eras.firstOrNull { it.id == query.era }?.title ?: "Any Year",
			listOf(Option(null, "Any Year")) + catalogue.eras.map { Option(it.id, it.title) },
		) { id -> onEdit { it.copy(era = id) } }
		PickerRow(
			1, 4, "Language",
			options.languages.firstOrNull { it.code == query.language }?.name ?: query.language ?: "Any Language",
			listOf(Option(null, "Any Language")) + options.languages.map { Option(it.code, it.name) },
		) { id -> onEdit { it.copy(language = id) } }
		PickerRow(
			2, 4, "Rating",
			query.minRating?.let { "$it+" } ?: "Any Rating",
			listOf(Option(null, "Any Rating")) + RATINGS.map { Option(it.toString(), "$it+") },
		) { id -> onEdit { it.copy(minRating = id?.toInt()) } }
		PickerRow(
			3, 4, "Runtime",
			catalogue.runtimes.firstOrNull { it.id == query.runtime }?.title(query.kind) ?: "Any Length",
			listOf(Option(null, "Any Length")) + catalogue.runtimes.map { Option(it.id, it.title(query.kind)) },
		) { id -> onEdit { it.copy(runtime = id) } }
		Spacer()

		PickerRow(
			0, 2, "Region",
			options.regions.firstOrNull { it.code == region }?.name ?: region,
			options.regions.map { Option(it.code, it.name) },
		) { id -> if (id != null) onRegion(id) }
		PickerRow(
			1, 2, "Streaming",
			options.providers.firstOrNull { it.id == query.streaming?.provider }?.name ?: "Any Service",
			listOf(Option(null, "Any Service")) + options.providers.map { Option(it.id.toString(), it.name) },
		) { id -> onStreaming(id?.toInt(), region) }
		Spacer()

		SwitchRow(query.upcoming) { on -> onEdit { it.copy(upcoming = on) } }
		if (query.hasMenuFilters) TextAction("Clear Filters", onClear)
	}
}

@Composable
private fun Spacer() {
	Box(Modifier.padding(top = Space.s3))
}

private val RATINGS = listOf(9, 8, 7, 6, 5)

@Composable
private fun PickerRow(index: Int, count: Int, label: String, value: String, options: List<Option>, onPick: (String?) -> Unit) {
	var open by remember { mutableStateOf(false) }
	GroupRow(index, count, onClick = { open = true }) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Box {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.s1),
				modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
			) {
				Text(value, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
				Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = lucent.ink3, modifier = Modifier.size(20.dp))
			}
			DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 200.dp)) {
				options.forEach { option ->
					DropdownMenuItem(
						text = { Text(option.label) },
						onClick = {
							open = false
							onPick(option.id)
						},
					)
				}
			}
		}
	}
}

/** The sort's two directions, named as Core names them for that sort. */
@Composable
private fun OrderRow(index: Int, count: Int, sort: SeerrCatalogue.Sort?, reversed: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(index, count) {
		Text("Order", style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 260.dp)) {
			listOf(false to (sort?.first ?: "First"), true to (sort?.reversed ?: "Last")).forEachIndexed { position, (value, label) ->
				SegmentedButton(
					selected = reversed == value,
					onClick = { onChange(value) },
					shape = SegmentedButtonDefaults.itemShape(position, 2),
				) { Text(label, maxLines = 1) }
			}
		}
	}
}

@Composable
private fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(0, 1, modifier = Modifier.fillMaxWidth(), onClick = { onChange(!checked) }) {
		Text("Upcoming Only", style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = null)
	}
}

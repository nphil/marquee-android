package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.browse.ServerLibrary
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexMedia
import com.nphil.marquee.core.SectionQuery
import com.nphil.marquee.data.PosterLabels
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.browse.LocalPosterLabels
import com.nphil.marquee.ui.browse.PosterGridMetrics
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.HeaderStyle
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun HeaderStylePage(style: HeaderStyle, padding: PaddingValues, onChange: (HeaderStyle) -> Unit) {
	BrowseList(Modifier.fillMaxSize(), contentPadding = padding) {
		item {
			AppearanceOptions(HeaderStyle.entries, style, title = { it.name }, detail = { it.description }, onChange = onChange)
		}
	}
}

@Composable
internal fun UnderPostersPage(container: AppContainer, profile: String, padding: PaddingValues) {
	val labels = LocalPosterLabels.current
	val update: ((PosterLabels) -> PosterLabels) -> Unit = { change ->
		container.scope.launch { container.settings.updatePosterLabels(profile, change) }
	}
	BrowseList(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		item(key = "preview") { PosterPreview(container, profile) }
		item(key = "title") {
			AppearanceSwitch(0, 2, "Title", checked = labels.showsTitle) { on ->
				update { it.copy(showsTitle = on) }
			}
		}
		item(key = "sort") {
			AppearanceSwitch(1, 2, "Sort Value", "Replaces details in a sorted library", labels.showsSortValue) { on ->
				update { it.copy(showsSortValue = on) }
			}
		}
		item(key = "details-label") { Heading("Details") }
		item(key = "details") {
			AppearanceOptions(
				PosterLabels.Details.entries, labels.details, title = { it.title },
				detail = { if (it == PosterLabels.Details.Video) "TV shows display their year" else null },
			) { next ->
				update { it.copy(details = next) }
			}
		}
		item(key = "reset") {
			Column(Modifier.padding(top = Space.s4)) {
				SettingsActionRow(0, 1, R.drawable.ic_refresh, "Reset to Default", enabled = labels != PosterLabels(), onClick = {
					update { PosterLabels() }
				})
			}
		}
	}
}

@Composable
internal fun AppearanceSwitch(index: Int, count: Int, title: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(index, count, modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
		AppearanceText(title, detail, Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = null)
	}
}

@Composable
private fun <T> AppearanceOptions(options: List<T>, value: T, title: (T) -> String, detail: (T) -> String? = { null }, onChange: (T) -> Unit) {
	Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		options.forEachIndexed { index, option ->
			val selected = option == value
			GroupRow(
				index, options.size, selected = selected,
				modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = { onChange(option) }),
			) {
				AppearanceText(title(option), detail(option), Modifier.weight(1f))
				RadioButton(selected, onClick = null)
			}
		}
	}
}

@Composable
private fun AppearanceText(title: String, detail: String?, modifier: Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
		Text(title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
		if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
	}
}

@Composable
private fun PosterPreview(container: AppContainer, profile: String) {
	val place by container.browse.source.place.collectAsStateWithLifecycle()
	val recent = key(place?.key, profile) {
		produceState(emptyList<PlexItem>()) {
			if (place?.profile != profile) return@produceState
			try {
				val library = ServerLibrary(container.browse.source)
				val last = container.browse.preferences.libraryChoice(profile).first()
				val sections = library.sections().filter { it.type == "movie" || it.type == "show" }
				val section = sections.firstOrNull { it.key == last } ?: sections.firstOrNull() ?: return@produceState
				value = library.titles(section.key, SectionQuery(section.itemType, SectionQuery.Sort.RecentlyAdded), 0, 3).items
			} catch (_: CoreException) {
				// Local examples still demonstrate the selected labels when Plex is offline.
			}
		}.value
	}
	val items = recent.ifEmpty { Examples }
	Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), horizontalAlignment = Alignment.CenterHorizontally) {
		BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
			val usable = minOf(maxWidth, Size.gridMin * 3 + Space.s3 * 2)
			val metrics = PosterGridMetrics.of(usable, LocalDensity.current.fontScale, minCell = minOf(usable, Size.gridMin))
			Row(horizontalArrangement = Arrangement.spacedBy(metrics.gutter)) {
				for (item in items.take(metrics.columns)) ItemPoster(item, metrics.cellWidth, onClick = null)
			}
		}
		SettingsFootnote(if (recent.isEmpty()) "Example posters" else "Library preview", Modifier.padding(top = Space.s2))
	}
}

private val Examples = listOf(
	PlexItem("preview.movie", type = MediaType.Movie, title = "Movie", year = 2019, duration = 8_040_000,
		genres = listOf("Drama"), contentRating = "PG-13", audienceRating = 7.8, media = listOf(PlexMedia(videoResolution = "4k"))),
	PlexItem("preview.show", type = MediaType.Show, title = "Show", year = 2019, childCount = 6,
		genres = listOf("Drama"), contentRating = "TV-14", audienceRating = 8.4),
	PlexItem("preview.collection", type = MediaType.Collection, title = "Collection", childCount = 3),
)

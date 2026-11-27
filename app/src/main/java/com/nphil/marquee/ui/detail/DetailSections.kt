package com.nphil.marquee.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.nphil.marquee.R
import com.nphil.marquee.core.AudioChoice
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.PlexPart
import com.nphil.marquee.core.PlexRole
import com.nphil.marquee.core.SubtitleChoice
import com.nphil.marquee.core.versionLabel
import com.nphil.marquee.ui.Navigator
import com.nphil.marquee.ui.browse.ItemPoster
import com.nphil.marquee.ui.components.LocalPlexImages
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.SectionHeader
import com.nphil.marquee.ui.components.ShelfRow
import com.nphil.marquee.ui.components.cardHeight
import com.nphil.marquee.ui.posterSharedElement
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlin.math.ceil

private const val CastLimit = 30
private val ShelfPoster = 130.dp
private val ExtraWidth = 220.dp
private val CastPhoto = 72.dp

/**
 * A title's clear logo in place of its name. It needs the image as the server has it, with the clear parts
 * kept, so it skips [PlexArt]'s track-coloured backing; without a connection the name stands in.
 */
@Composable
internal fun LogoArt(path: String, title: String, width: Dp = 280.dp, height: Dp = 96.dp) {
	val images = LocalPlexImages.current
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val widthPx = ceil(width.value * density).toInt()
	val heightPx = ceil(height.value * density).toInt()
	val request = remember(images, path, widthPx, heightPx) { images?.request(context, path, widthPx, heightPx) }
	val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit)
	val state by painter.state.collectAsState()
	Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
		if (request != null && state is AsyncImagePainter.State.Success) {
			Image(painter, title, Modifier.size(width, height), alignment = Alignment.CenterStart, contentScale = ContentScale.Fit)
		} else {
			Text(title, style = MaterialTheme.typography.displaySmall, color = lucent.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** A quiet rounded row: a label, the current value and a chevron. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.widthIn(max = 560.dp)
			.heightIn(min = Size.rowMin)
			.clip(RoundedCornerShape(Radius.row))
			.background(lucent.sheet)
			.border(1.dp, lucent.edge, RoundedCornerShape(Radius.row))
			.clickable(role = Role.Button, onClick = onClick)
			.padding(horizontal = Space.s4),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Text(label, style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
		Text(
			value,
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f),
		)
		Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(20.dp))
	}
}

/** Picks which file of a title with several: the same cut at another resolution, say. */
@Composable
internal fun VersionMenu(item: PlexItem, selected: Int, onSelect: (Int) -> Unit) {
	var open by remember { mutableStateOf(false) }
	val current = item.media.getOrNull(selected) ?: return
	Box {
		ValueRow("Version", current.versionLabel, onClick = { open = true })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			item.media.forEachIndexed { index, media ->
				DropdownMenuItem(
					text = { Text(media.versionLabel) },
					leadingIcon = if (index == selected) ({ Icon(painterResource(R.drawable.ic_check), contentDescription = null) }) else null,
					modifier = Modifier.semantics { stateDescription = if (index == selected) "Selected" else "" },
					onClick = {
						open = false
						onSelect(index)
					},
				)
			}
		}
	}
}

/** The summary, three lines until tapped. */
@Composable
internal fun Synopsis(text: String) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	var overflows by remember { mutableStateOf(false) }
	val toggles = overflows || expanded
	Row(
		modifier = Modifier
			.widthIn(max = 560.dp)
			.animateContentSize()
			.clickable(enabled = toggles, role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded },
		verticalAlignment = Alignment.Top,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink,
			maxLines = if (expanded) Int.MAX_VALUE else 3,
			overflow = TextOverflow.Ellipsis,
			onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
			modifier = Modifier.weight(1f, fill = false),
		)
		if (toggles) {
			Icon(
				painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = lucent.ink2,
				modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
			)
		}
	}
}

/** The file's audio and subtitle choices at a glance; the row opens the full list. */
@Composable
internal fun TracksRow(part: PlexPart?, onClick: () -> Unit) {
	val tracks = part?.tracks ?: return
	if (tracks.audio.isEmpty() && tracks.subtitles.isEmpty()) return
	val audio = (tracks.audio.firstOrNull { it.isDefault } ?: tracks.audio.firstOrNull())?.label
	val subtitles = when (tracks.subtitles.size) {
		0 -> "No subtitles"
		1 -> "1 subtitle"
		else -> "${tracks.subtitles.size} subtitles"
	}
	ValueRow("Audio & Subtitles", listOfNotNull(audio, subtitles).joinToString(" · "), onClick)
}

/** Everything on one file: each audio track with its format, then each subtitle (DESIGN §8.3 item 6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracksSheet(part: PlexPart, onDismiss: () -> Unit) {
	val tracks = part.tracks ?: return
	ModalBottomSheet(onDismissRequest = onDismiss, containerColor = lucent.sheet, contentColor = lucent.ink) {
		LazyColumn(
			contentPadding = PaddingValues(start = Space.inset, end = Space.inset, bottom = Space.s6),
			verticalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			item(key = "audio") { Text("Audio", style = MaterialTheme.typography.headlineSmall, color = lucent.ink) }
			if (tracks.audio.isEmpty()) {
				item(key = "no-audio") { Text("No audio tracks", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2) }
			}
			items(tracks.audio, key = { "a${it.id}" }) { track -> TrackRow(track.label, audioDetail(track), audioChips(track, part)) }
			item(key = "subtitles") {
				Text("Subtitles", style = MaterialTheme.typography.headlineSmall, color = lucent.ink, modifier = Modifier.padding(top = Space.s3))
			}
			if (tracks.subtitles.isEmpty()) {
				item(key = "no-subtitles") { Text("No subtitles", style = MaterialTheme.typography.bodyLarge, color = lucent.ink2) }
			}
			items(tracks.subtitles, key = { "s${it.id}" }) { track -> TrackRow(track.label, subtitleDetail(track), subtitleChips(track)) }
		}
	}
}

@Composable
private fun TrackRow(label: String, detail: String, chips: List<String>) {
	Row(Modifier.fillMaxWidth().heightIn(min = Size.rowMin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyLarge, color = lucent.ink)
			if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		}
		chips.forEach { Chip(it) }
	}
}

@Composable
internal fun Chip(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelSmall,
		color = lucent.ink2,
		modifier = Modifier.border(1.dp, lucent.edge, RoundedCornerShape(6.dp)).padding(horizontal = Space.s2, vertical = 2.dp),
	)
}

/** "EAC3 5.1". */
internal fun audioDetail(track: AudioChoice): String =
	listOfNotNull(track.codec.takeIf { it.isNotEmpty() }?.uppercase(), track.channels?.let(::channelLayout)).joinToString(" ")

internal fun channelLayout(channels: Int): String = when (channels) {
	1 -> "1.0"
	2 -> "2.0"
	3 -> "2.1"
	6 -> "5.1"
	7 -> "6.1"
	8 -> "7.1"
	else -> "$channels ch"
}

private val Dolby = setOf("ac3", "eac3", "truehd")

/** The marks a track earns: PCM, Dolby, Atmos (a stream Plex names so), and the ones Core gave it. */
internal fun audioChips(track: AudioChoice, part: PlexPart): List<String> = buildList {
	if (track.codec.startsWith("pcm")) add("PCM")
	if (track.codec in Dolby) add("Dolby")
	val stream = part.streams.firstOrNull { it.id == track.id }
	if (stream?.extendedDisplayTitle?.contains("Atmos", ignoreCase = true) == true) add("Atmos")
	addAll(track.badges)
}.distinct()

internal fun subtitleDetail(track: SubtitleChoice): String =
	listOfNotNull(track.codec.takeIf { it.isNotEmpty() }?.uppercase(), "External".takeIf { track.isExternal }).joinToString(" · ")

/** PGS, VOBSUB and DVB are pictures: they cannot be restyled or searched, so they say so. */
internal fun subtitleChips(track: SubtitleChoice): List<String> = (listOfNotNull(track.imageFormat) + track.badges).distinct()

fun LazyListScope.castSection(item: PlexItem, navigator: Navigator) {
	val cast = item.roles.take(CastLimit).withIndex().toList()
	if (cast.isEmpty()) return
	item(key = "cast", contentType = "shelf") {
		Column(Modifier.padding(top = Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
			SectionHeader("Cast")
			ShelfRow(cast, key = { it.index }) { (_, role) -> CastCard(role, navigator) }
		}
	}
}

@Composable
private fun CastCard(role: PlexRole, navigator: Navigator) {
	val opens = role.id != null
	Column(
		modifier = Modifier
			.size(width = CastPhoto + Space.s4, height = CastPhoto + Space.s8 + Space.s4)
			.clip(RoundedCornerShape(Radius.control))
			.then(if (opens) Modifier.clickable(role = Role.Button, onClickLabel = "Titles with ${role.name}") { navigator.openPerson(role) } else Modifier),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		if (role.thumb != null) {
			PlexArt(role.thumb, CastPhoto, CastPhoto, Modifier.clip(CircleShape))
		} else {
			Box(Modifier.size(CastPhoto).clip(CircleShape).background(lucent.trackOff), contentAlignment = Alignment.Center) {
				Text(initials(role.name), style = MaterialTheme.typography.titleMedium, color = lucent.ink2)
			}
		}
		Text(role.name, style = MaterialTheme.typography.labelLarge, color = lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
		role.character?.let {
			Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
		}
	}
}

internal fun initials(name: String): String = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

fun LazyListScope.extrasSection(state: DetailState, navigator: Navigator) {
	val extras = state.extras
	if (extras.isEmpty()) return
	item(key = "extras", contentType = "shelf") {
		Column(Modifier.padding(top = Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
			SectionHeader("Extras")
			ShelfRow(extras, key = { it.ratingKey }) { extra ->
				Column(
					modifier = Modifier
						.size(width = ExtraWidth, height = cardHeight(ExtraWidth) + Space.s8 + Space.s2)
						.clip(RoundedCornerShape(Radius.tile))
						.clickable(role = Role.Button, onClickLabel = "Play") { navigator.openExtra(extra) },
					verticalArrangement = Arrangement.spacedBy(Space.s1),
				) {
					PlexArt(extra.thumb, ExtraWidth, cardHeight(ExtraWidth), Modifier.clip(RoundedCornerShape(Radius.tile)))
					Text(extra.title, style = MaterialTheme.typography.labelLarge, color = lucent.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
					Text(
						listOfNotNull(extraKind(extra), ItemText.runtime(extra.duration)).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = lucent.ink2,
						maxLines = 1,
					)
				}
			}
		}
	}
}

/** What an extra is called, from Plex's own word for it (the iPhone's names). */
internal fun extraKind(extra: PlexItem): String {
	when (extra.subtype) {
		"trailer" -> return "Trailer"
		"deletedScene" -> return "Deleted Scene"
		"interview" -> return "Interview"
		"behindTheScenes" -> return "Behind the Scenes"
		"sceneOrSample" -> return "Scene"
		"featurette" -> return "Featurette"
	}
	return when (extra.extraType) {
		1 -> "Trailer"
		2 -> "Deleted Scene"
		3 -> "Interview"
		5 -> "Behind the Scenes"
		6 -> "Scene"
		else -> "Extra"
	}
}

fun LazyListScope.relatedSection(state: DetailState, navigator: Navigator) {
	state.related.forEach { hub ->
		item(key = "related-${hub.identifier}-${hub.key}", contentType = "shelf") {
			Column(Modifier.padding(top = Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				SectionHeader(hub.title)
				// External hubs list titles the server may not have: the Watchlist's rule decides where they open.
				val external = hub.identifier.startsWith("external")
				ShelfRow(hub.items, key = { it.ratingKey }) { related ->
					ItemPoster(
						item = related,
						width = ShelfPoster,
						onClick = { if (external) navigator.openWatchlistTitle(related) else navigator.openItem(related.ratingKey) },
					)
				}
			}
		}
	}
}

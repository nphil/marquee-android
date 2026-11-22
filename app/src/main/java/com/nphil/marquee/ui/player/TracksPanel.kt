package com.nphil.marquee.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.DELIVERY_IN_STREAM
import com.nphil.marquee.core.DELIVERY_SIDECAR
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.data.SubtitleColour
import com.nphil.marquee.data.SubtitleEdge
import com.nphil.marquee.data.SubtitleFont
import com.nphil.marquee.data.SubtitleLook
import com.nphil.marquee.data.SubtitlePreset
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.components.ChoiceRow
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.settings.SettingsSwitchRow
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** How long the track panel stays up with no touch on it, then it closes: a bright panel does not sit on the picture. */
internal const val PANEL_DWELL_MS = 30_000L

private val PANEL_WIDTH = 380.dp

/** The pages of the track panel. */
internal enum class TracksPage(val title: String) {
	Subtitles("Subtitles"),
	Audio("Audio"),
	Style("Subtitle Style"),
	Advanced("Advanced"),
	;

	/** The page a back press returns to; null when the panel closes instead. */
	val parent: TracksPage? get() = if (this == Style || this == Advanced) Subtitles else null
}

/**
 * The audio and subtitle menus over the right of the picture: pick a track, set how the lines look, or see every
 * subtitle the file has. A pick takes effect at once. [onTouch] hears every touch on the panel, for the dwell.
 */
@Composable
internal fun TracksPanel(
	model: PlayerModel,
	look: SubtitleLook,
	onLook: ((SubtitleLook) -> SubtitleLook) -> Unit,
	page: TracksPage,
	onPage: (TracksPage) -> Unit,
	onClose: () -> Unit,
	onTouch: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier
			.fillMaxHeight()
			.widthIn(max = PANEL_WIDTH)
			.safeDrawingPadding()
			.padding(Space.s3)
			.background(lucent.canvas, RoundedCornerShape(Radius.sheet))
			.padding(Space.s3)
			// Watches without taking the touch: the rows under it still get it, and nothing falls through to the picture.
			.pointerInput(onTouch) {
				awaitPointerEventScope {
					while (true) {
						awaitPointerEvent(PointerEventPass.Initial)
						onTouch()
					}
				}
			},
		verticalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Header(page, onBack = page.parent?.let { parent -> { onPage(parent) } }, onClose = onClose)
		key(page) {
			Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				when (page) {
					TracksPage.Subtitles -> SubtitlesPage(model, look, onPage)
					TracksPage.Audio -> AudioPage(model)
					TracksPage.Style -> StylePage(look, onLook)
					TracksPage.Advanced -> AdvancedPage(model)
				}
			}
		}
	}
}

@Composable
private fun Header(page: TracksPage, onBack: (() -> Unit)?, onClose: () -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		if (onBack != null) {
			IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = lucent.ink) }
		}
		Text(
			page.title,
			style = MaterialTheme.typography.titleLarge,
			color = lucent.ink,
			modifier = Modifier.weight(1f).padding(start = if (onBack == null) Space.s3 else 0.dp),
		)
		IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = lucent.ink) }
	}
}

@Composable
private fun SubtitlesPage(model: PlayerModel, look: SubtitleLook, onPage: (TracksPage) -> Unit) {
	val tracks = model.tracks
	val showing = tracks.lists.subtitles.firstOrNull { it.id == tracks.subtitleId }
	val automatic = tracks.lists.subtitles.firstOrNull { it.id == tracks.lists.automatic }
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		PickRow(0, 2, "Off", selected = showing == null) { model.pickSubtitle(SubtitlePick.Off) }
		PickRow(
			1, 2, "On",
			selected = showing != null,
			detail = (showing ?: automatic)?.label ?: "None available",
			enabled = showing != null || automatic != null,
		) {
			if (showing == null) model.pickSubtitle(SubtitlePick.Automatic)
		}
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		NavRow(0, 2, "Subtitle Style", look.preset.title.takeIf { look.isPreset } ?: "Custom") { onPage(TracksPage.Style) }
		NavRow(1, 2, "Advanced", null) { onPage(TracksPage.Advanced) }
	}
}

@Composable
private fun AudioPage(model: PlayerModel) {
	val tracks = model.tracks
	val options = tracks.lists.audio
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		options.forEachIndexed { index, option ->
			PickRow(index, options.size, option.label, selected = option.id == tracks.audioId) { model.pickAudio(option.id) }
		}
	}
}

/** Every subtitle the file has, for picking one by name. The ones this tablet cannot draw say so and cannot be picked. */
@Composable
private fun AdvancedPage(model: PlayerModel) {
	val tracks = model.tracks
	val options = tracks.lists.menuSubtitles
	if (options.isEmpty()) {
		Text("This title has no subtitles.", style = MaterialTheme.typography.bodyMedium, color = lucent.ink2, modifier = Modifier.padding(Space.s3))
		return
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		options.forEachIndexed { index, option ->
			val shown = option.delivery == DELIVERY_SIDECAR || option.delivery == DELIVERY_IN_STREAM
			PickRow(
				index, options.size, option.label,
				selected = option.id == tracks.subtitleId,
				detail = if (shown) null else "Not shown on this tablet",
				enabled = shown,
			) { model.pickSubtitle(SubtitlePick.track(option.id)) }
		}
	}
}

@Composable
private fun StylePage(look: SubtitleLook, onLook: ((SubtitleLook) -> SubtitleLook) -> Unit) {
	val presets = SubtitlePreset.entries
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		presets.forEachIndexed { index, preset ->
			PickRow(index, presets.size, preset.title, selected = look == SubtitleLook.of(preset)) { onLook { SubtitleLook.of(preset) } }
		}
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		ChoiceRow("Size", look.size, SubtitleLook.SIZES, { "$it%" }, 0, 6) { size -> onLook { it.copy(size = size) } }
		ChoiceRow("Font", look.font, SubtitleFont.entries, { it.name }, 1, 6) { font -> onLook { it.copy(font = font) } }
		ChoiceRow("Colour", look.colour, SubtitleColour.entries, { it.title }, 2, 6) { colour -> onLook { it.copy(colour = colour) } }
		ChoiceRow("Edge", look.edge, SubtitleEdge.entries, { it.title() }, 3, 6) { edge -> onLook { it.copy(edge = edge) } }
		SettingsSwitchRow(4, 6, "Bold", look.bold, enabled = true) { bold -> onLook { it.copy(bold = bold) } }
		SettingsSwitchRow(5, 6, "Background", look.box, enabled = true) { box -> onLook { it.copy(box = box) } }
	}
}

private fun SubtitleEdge.title() = when (this) {
	SubtitleEdge.None -> "None"
	SubtitleEdge.Outline -> "Outline"
	SubtitleEdge.DropShadow -> "Drop Shadow"
	SubtitleEdge.SoftShadow -> "Soft Shadow"
}

/** A row of a list of choices: the chosen one is marked, and one that cannot be chosen is dimmed. */
@Composable
private fun PickRow(
	index: Int,
	count: Int,
	label: String,
	selected: Boolean,
	detail: String? = null,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	GroupRow(index, count, selected = selected, onClick = if (enabled) onClick else null) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) lucent.ink else lucent.ink3)
			if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		}
		if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", modifier = Modifier.size(20.dp), tint = lucent.ink)
	}
}

/** A row that opens another page, showing where it stands now. */
@Composable
private fun NavRow(index: Int, count: Int, label: String, value: String?, onClick: () -> Unit) {
	GroupRow(index, count, onClick = onClick) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
		Icon(
			painterResource(R.drawable.ic_arrow_back),
			contentDescription = null,
			modifier = Modifier.size(20.dp).rotate(180f),
			tint = lucent.ink2,
		)
	}
}

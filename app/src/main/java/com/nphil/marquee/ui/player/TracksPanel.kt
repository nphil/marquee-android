package com.nphil.marquee.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.cast.CastProvider
import com.nphil.marquee.core.DELIVERY_BURNED_IN
import com.nphil.marquee.core.DELIVERY_IN_STREAM
import com.nphil.marquee.core.DELIVERY_SIDECAR
import com.nphil.marquee.core.SubtitlePick
import com.nphil.marquee.data.SubtitleColour
import com.nphil.marquee.data.SubtitleEdge
import com.nphil.marquee.data.SubtitleFont
import com.nphil.marquee.data.SubtitleLook
import com.nphil.marquee.data.SubtitlePreset
import com.nphil.marquee.data.SubtitleReveal
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.components.ChoiceRow
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.settings.SettingsActionRow
import com.nphil.marquee.ui.settings.SettingsFootnote
import com.nphil.marquee.ui.settings.SettingsSwitchRow
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** How long the panel stays up with no touch on it, then it closes: a bright panel does not sit on the picture. */
internal const val PANEL_DWELL_MS = 30_000L

private val PANEL_WIDTH = 380.dp

/** The pages of the player's side panel. */
internal enum class TracksPage(val title: String) {
	Subtitles("Subtitles"),
	Audio("Audio"),
	Style("Subtitle Style"),
	Advanced("Advanced"),
	More("More"),
	Quality("Quality"),
	Speed("Playback Speed"),
	Aspect("Aspect Ratio"),
	Chapters("Chapters"),
	Sleep("Sleep Timer"),
	Info("Playback Info"),
}

/**
 * The menus over the right of the picture: the audio and subtitle tracks, the settings of the picture and the sound,
 * and the playback info. [pages] is the trail of pages opened, the last one showing; a page opened from another has a
 * way back. A pick takes effect at once. [onTouch] hears every touch on the panel, for the dwell. The panel keeps the
 * app's theme, not the player's dark one: it is a sheet, and it is on screen only while the viewer is reading it.
 */
@Composable
internal fun TracksPanel(
	model: PlayerModel,
	look: SubtitleLook,
	onLook: ((SubtitleLook) -> SubtitleLook) -> Unit,
	pages: List<TracksPage>,
	aspect: PlayerAspect,
	onAspect: (PlayerAspect) -> Unit,
	sleep: SleepTimer,
	pip: PictureInPicture,
	onPush: (TracksPage) -> Unit,
	onPop: () -> Unit,
	onClose: () -> Unit,
	onTouch: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val page = pages.last()
	val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
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
		Header(page, onBack = if (pages.size > 1) onPop else null, onClose = onClose)
		AnimatedContent(
			page,
			Modifier.weight(1f),
			transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
			label = "panel page",
		) { shown ->
			Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
				when (shown) {
					TracksPage.Subtitles -> SubtitlesPage(model, look, onPush, onClose)
					TracksPage.Audio -> AudioPage(model)
					TracksPage.Style -> StylePage(look, onLook)
					TracksPage.Advanced -> AdvancedPage(model, onClose)
					TracksPage.More -> MorePage(model, pip, aspect, sleep, onPush, onClose)
					TracksPage.Quality -> QualityPage(model)
					TracksPage.Speed -> SpeedPage(model)
					TracksPage.Aspect -> AspectPage(aspect, onAspect)
					TracksPage.Chapters -> ChaptersPage(model, onClose)
					TracksPage.Sleep -> SleepPage(model, sleep)
					TracksPage.Info -> PlaybackInfoPage(model)
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
private fun SubtitlesPage(model: PlayerModel, look: SubtitleLook, onPush: (TracksPage) -> Unit, onClose: () -> Unit) {
	val tracks = model.tracks
	val showing = tracks.lists.subtitles.firstOrNull { it.id == tracks.subtitleId }
	val casting = model.castReceiver != null
	if (model.castReceiver?.id?.provider == CastProvider.AirPlay) {
		SettingsFootnote("Subtitles aren’t available over AirPlay.")
		return
	}
	if (casting) {
		Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
			PickRow(0, 2, "Off", selected = showing == null) { model.pickSubtitle(SubtitlePick.Off) }
			NavRow(1, 2, "Advanced", showing?.label) { onPush(TracksPage.Advanced) }
		}
		SettingsFootnote("Text subtitles aren't shown while casting. Compatible picture subtitles can be picked in Advanced.")
		return
	}
	val automatic = tracks.lists.subtitles.firstOrNull { it.id == tracks.lists.automatic }
	val canFind = model.canFindSubtitles && !model.subtitleFinder.busy
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		PickRow(0, 2, "Off", selected = showing == null) { model.pickSubtitle(SubtitlePick.Off) }
		PickRow(
			1, 2, "On",
			selected = showing != null,
			detail = (showing ?: automatic)?.label ?: if (model.canFindSubtitles) "Find English subtitles" else "None available",
			enabled = showing != null || automatic != null || canFind,
		) {
			if (showing == null) {
				if (automatic != null) model.pickSubtitle(SubtitlePick.Automatic)
				else {
					model.findSubtitles()
					onClose()
				}
			}
		}
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		NavRow(0, 2, "Subtitle Style", look.preset.title.takeIf { look.isPreset } ?: "Custom") { onPush(TracksPage.Style) }
		NavRow(1, 2, "Advanced", null) { onPush(TracksPage.Advanced) }
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
private fun AdvancedPage(model: PlayerModel, onClose: () -> Unit) {
	val tracks = model.tracks
	val casting = model.castReceiver != null
	val options = tracks.lists.menuSubtitles
	if (options.isEmpty()) {
		Text("This title has no subtitles.", style = MaterialTheme.typography.bodyMedium, color = lucent.ink2, modifier = Modifier.padding(Space.s3))
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		options.forEachIndexed { index, option ->
			val shown = option.delivery == DELIVERY_SIDECAR || option.delivery == DELIVERY_IN_STREAM || option.delivery == DELIVERY_BURNED_IN
			PickRow(
				index, options.size, option.label,
				selected = option.id == tracks.subtitleId,
				detail = if (shown) null else if (casting) "Not shown while casting" else "Not shown on this tablet",
				enabled = shown,
			) { model.pickSubtitle(SubtitlePick.track(option.id)) }
		}
	}
	if (model.canFindSubtitles) {
		SettingsActionRow(
			0, 1, R.drawable.ic_search, "Find Subtitles", enabled = !model.subtitleFinder.busy,
			onClick = { model.findSubtitles(); onClose() },
		)
	}
}

@Composable
private fun StylePage(look: SubtitleLook, onLook: ((SubtitleLook) -> SubtitleLook) -> Unit) {
	val presets = SubtitlePreset.entries
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		presets.forEachIndexed { index, preset ->
			PickRow(index, presets.size, preset.title, selected = look.preset == preset && look.isPreset) {
				onLook { SubtitleLook.of(preset, reveal = it.reveal) }
			}
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
	ChoiceRow("Reveal as Spoken", look.reveal, SubtitleReveal.entries, { it.title }, 0, 1) { mode ->
		onLook { it.copy(reveal = mode) }
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
internal fun PickRow(
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
internal fun NavRow(index: Int, count: Int, label: String, value: String?, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
	GroupRow(index, count, onClick = onClick) {
		if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = lucent.ink, modifier = Modifier.size(24.dp))
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, color = lucent.ink2)
		Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp), tint = lucent.ink2)
	}
}

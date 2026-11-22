package com.nphil.marquee.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.Chapter
import com.nphil.marquee.core.MediaType
import com.nphil.marquee.core.QualityPreset
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.settings.SettingsActionRow
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.lucent

/** The playback speeds on the menu. */
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The lengths of the sleep timer on the menu, in minutes. */
private val SLEEP_MINUTES = listOf(15, 30, 60)

private val CHAPTER_ART_WIDTH = 96.dp
private val CHAPTER_ART_HEIGHT = 54.dp

private class NavEntry(@DrawableRes val icon: Int, val label: String, val value: String?, val page: TracksPage)

/** A speed as the menu and the controls name it: Normal, or 1.5×. */
internal fun speedTitle(speed: Float): String = if (speed == 1f) "Normal" else speedLabel(speed)

/** What stopping when the title ends is called for the title that is playing. */
private fun endTitle(model: PlayerModel): String = when (model.plan?.item?.type) {
	MediaType.Episode -> "End of Episode"
	MediaType.Movie -> "End of Movie"
	else -> "End of Video"
}

private fun sleepSummary(model: PlayerModel, sleep: SleepTimer): String = when {
	model.sleepAtEnd -> endTitle(model)
	sleep.set -> "${sleep.minutesLeft()} min left"
	else -> "Off"
}

/** The More menu: the settings of the picture and the sound, and the tools for looking at how the title plays. */
@Composable
internal fun MorePage(
	model: PlayerModel,
	pip: PictureInPicture,
	aspect: PlayerAspect,
	sleep: SleepTimer,
	onPush: (TracksPage) -> Unit,
	onClose: () -> Unit,
) {
	val settings = buildList {
		add(NavEntry(R.drawable.ic_tune, "Quality", QualityPreset.title(model.quality), TracksPage.Quality))
		add(NavEntry(R.drawable.ic_aspect_ratio, "Aspect Ratio", aspect.label, TracksPage.Aspect))
		add(NavEntry(R.drawable.ic_speed, "Playback Speed", speedTitle(model.speed), TracksPage.Speed))
		if (model.plan?.chapters?.isNotEmpty() == true) add(NavEntry(R.drawable.ic_chapters, "Chapters", null, TracksPage.Chapters))
		add(NavEntry(R.drawable.ic_bedtime, "Sleep Timer", sleepSummary(model, sleep), TracksPage.Sleep))
	}
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		settings.forEachIndexed { index, entry -> NavRow(index, settings.size, entry.label, entry.value, entry.icon) { onPush(entry.page) } }
	}
	val tools = if (pip.supported) 3 else 2
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		NavRow(0, tools, "Playback Info", null, R.drawable.ic_info) { onPush(TracksPage.Info) }
		IconSwitchRow(1, tools, R.drawable.ic_stats, "Stats for Nerds", model.showsStats) {
			model.toggleStats()
			onClose()
		}
		if (pip.supported) {
			SettingsActionRow(2, tools, R.drawable.ic_picture_in_picture, "Picture in Picture", enabled = true, onClick = {
				onClose()
				pip.enter()
			})
		}
	}
}

@Composable
private fun IconSwitchRow(index: Int, count: Int, @DrawableRes icon: Int, label: String, checked: Boolean, onChange: () -> Unit) {
	GroupRow(index, count, onClick = onChange) {
		Icon(painterResource(icon), contentDescription = null, tint = lucent.ink, modifier = Modifier.size(24.dp))
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = null)
	}
}

/** The rungs the title can be converted to, and Original. */
@Composable
internal fun QualityPage(model: PlayerModel) {
	val rungs = QualityPreset.all
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		rungs.forEachIndexed { index, rung ->
			PickRow(index, rungs.size, QualityPreset.title(rung), selected = rung == model.quality) { model.setQuality(rung) }
		}
	}
}

@Composable
internal fun SpeedPage(model: PlayerModel) {
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		SPEEDS.forEachIndexed { index, speed ->
			PickRow(index, SPEEDS.size, speedTitle(speed), selected = model.speed == speed) { model.setSpeed(speed) }
		}
	}
}

@Composable
internal fun AspectPage(aspect: PlayerAspect, onAspect: (PlayerAspect) -> Unit) {
	val options = PlayerAspect.entries
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		options.forEachIndexed { index, option ->
			PickRow(index, options.size, option.label, selected = option == aspect, detail = option.detail) { onAspect(option) }
		}
	}
}

/** Off, stopping when the title ends, or pausing after 15, 30 or 60 minutes. */
@Composable
internal fun SleepPage(model: PlayerModel, sleep: SleepTimer) {
	val count = 2 + SLEEP_MINUTES.size
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		PickRow(0, count, "Off", selected = !model.sleepAtEnd && !sleep.set) {
			model.sleepAtEnd = false
			sleep.cancel()
		}
		PickRow(1, count, endTitle(model), selected = model.sleepAtEnd) {
			sleep.cancel()
			model.sleepAtEnd = true
		}
		SLEEP_MINUTES.forEachIndexed { index, minutes ->
			val running = !model.sleepAtEnd && sleep.set && sleep.minutes == minutes
			PickRow(
				2 + index, count, "$minutes Minutes",
				selected = running,
				detail = if (running) "${sleep.minutesLeft()} min left" else null,
			) {
				model.sleepAtEnd = false
				sleep.start(minutes)
			}
		}
	}
}

/** The title's chapters with a picture of each when the stream has previews; one tap goes there. */
@Composable
internal fun ChaptersPage(model: PlayerModel, onClose: () -> Unit) {
	val chapters = model.plan?.chapters.orEmpty()
	val previews = model.previews
	// Reading the revision shows a picture that arrives after the page opened.
	previews?.revision
	val at = model.movieMs()
	Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
		chapters.forEachIndexed { index, chapter ->
			ChapterRow(
				index, chapters.size, chapter,
				number = index + 1,
				current = at >= chapter.start && at < chapter.end,
				art = previews?.frame(chapter.start / 1000.0),
			) {
				model.seekTo(chapter.start / 1000.0)
				onClose()
			}
		}
	}
}

@Composable
private fun ChapterRow(
	index: Int,
	count: Int,
	chapter: Chapter,
	number: Int,
	current: Boolean,
	art: ImageBitmap?,
	onClick: () -> Unit,
) {
	GroupRow(index, count, selected = current, onClick = onClick) {
		Box(
			Modifier.size(CHAPTER_ART_WIDTH, CHAPTER_ART_HEIGHT).clip(RoundedCornerShape(Radius.control)).background(lucent.glass),
			contentAlignment = Alignment.Center,
		) {
			if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(CHAPTER_ART_WIDTH, CHAPTER_ART_HEIGHT))
		}
		Column(Modifier.weight(1f)) {
			Text(
				chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter $number",
				style = MaterialTheme.typography.titleMedium,
				color = lucent.ink,
				maxLines = 2,
			)
			Text(clock(chapter.start.toLong()), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = lucent.ink2)
		}
	}
}

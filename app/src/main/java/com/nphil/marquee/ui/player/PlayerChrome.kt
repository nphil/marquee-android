package com.nphil.marquee.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.playback.ScrubPreviews
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space

/** The window width from which the top bar lists every action and the transport spreads out. */
private val WIDE = 600.dp

/**
 * From the bottom of the safe area up past the seek bar and the row under it. The offers and the captions sit this
 * far up, so they never run into the controls, whether or not the controls are showing.
 */
internal val CHROME_BOTTOM_CLEARANCE = 136.dp

/** What the controls show. The caller builds a new one as the position moves. */
@Immutable
internal class ChromeState(
	val title: String,
	val subtitle: String? = null,
	/** A player is open: otherwise only the way back and the title show. */
	val ready: Boolean = true,
	val playing: Boolean = false,
	val buffering: Boolean = false,
	val positionMs: Long = 0L,
	val durationMs: Long = 0L,
	val bufferedMs: Long = 0L,
	/** Where the bar is cut, as fractions of the title. */
	val cuts: List<Float> = emptyList(),
	/** The intro and credits, as stretches of the bar. */
	val bands: List<ClosedFloatingPointRange<Float>> = emptyList(),
	val skipSeconds: Int = 10,
	/** Whether the right-hand time counts down instead of showing the length. */
	val showsRemaining: Boolean = false,
	val speed: Float = 1f,
	val aspect: PlayerAspect = PlayerAspect.Fit,
	/** The title has subtitles to pick from, so the button to them shows. */
	val hasSubtitles: Boolean = false,
	val subtitlesOn: Boolean = false,
	val hasAudioChoices: Boolean = false,
	/** The quality chosen by hand, or null while it is automatic. */
	val qualityLabel: String? = null,
	val canPip: Boolean = false,
	val hasChapters: Boolean = false,
	val hasNext: Boolean = false,
)

/** What the controls do. */
@Immutable
internal class ChromeActions(
	val back: () -> Unit,
	val toggle: () -> Unit,
	val skip: (seconds: Int) -> Unit,
	val subtitles: () -> Unit,
	val audio: () -> Unit,
	val quality: () -> Unit,
	val speed: () -> Unit,
	val pip: () -> Unit,
	val more: () -> Unit,
	val lock: () -> Unit,
	val aspect: () -> Unit,
	val chapters: () -> Unit,
	val next: () -> Unit,
	val remaining: () -> Unit,
)

/** A playback speed as the controls write it: 1.5×, 0.75×, 2×. */
internal fun speedLabel(speed: Float): String =
	(if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "\u00D7"

/**
 * The player's controls, drawn over the picture: dark scrims at the top and bottom, the title and actions, the
 * transport in the middle, and the seek bar with the row under it. Light marks on dark shades on every theme; only the
 * accent comes from the theme. It holds no state of its own beyond where the bar is, so the caller decides when it
 * shows. While the bar is scrubbed everything but the bar fades, so the picture and the preview are clear.
 */
@Composable
internal fun PlayerChrome(
	state: ChromeState,
	actions: ChromeActions,
	scrub: ScrubState,
	previews: ScrubPreviews?,
	modifier: Modifier = Modifier,
) {
	val accent = rememberPlayerAccent()
	var bar by remember { mutableStateOf(Rect.Zero) }
	BoxWithConstraints(modifier.fillMaxSize()) {
		val wide = maxWidth >= WIDE
		val fade by animateFloatAsState(if (scrub.active) 0f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "chrome fade")
		Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(minOf(maxHeight * 0.3f, 200.dp)).background(PlayerInk.topScrim))
		Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(minOf(maxHeight * 0.4f, 260.dp)).background(PlayerInk.bottomScrim))
		Box(Modifier.fillMaxSize().safeDrawingPadding()) {
			TopBar(state, actions, accent, wide, Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = fade })
			if (state.ready) {
				Transport(state, actions, accent, wide, Modifier.align(Alignment.Center).graphicsLayer { alpha = fade })
				Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
					SeekRow(state, actions, scrub, accent) { bar = it }
					BottomRow(state, actions, accent, wide, Modifier.graphicsLayer { alpha = fade })
				}
			}
		}
		if (scrub.active) SeekPreview(scrub, state.durationMs, previews, { bar })
	}
}

@Composable
private fun TopBar(state: ChromeState, actions: ChromeActions, accent: Color, wide: Boolean, modifier: Modifier) {
	Row(
		modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		GlassButton(R.drawable.ic_arrow_back, "Back", actions.back)
		Column(Modifier.weight(1f).padding(horizontal = Space.s2)) {
			Text(
				state.title,
				style = shadowed(MaterialTheme.typography.titleLarge),
				color = PlayerInk.ink,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			state.subtitle?.let {
				Text(it, style = shadowed(MaterialTheme.typography.bodyMedium), color = PlayerInk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		if (!state.ready) return@Row
		if (state.hasSubtitles) GlassButton(R.drawable.ic_closed_caption, "Subtitles", actions.subtitles, tint = if (state.subtitlesOn) accent else PlayerInk.ink)
		if (state.hasAudioChoices) GlassButton(R.drawable.ic_volume_up, "Audio", actions.audio)
		if (wide) {
			GlassButton(R.drawable.ic_tune, "Quality", actions.quality, tint = if (state.qualityLabel != null) accent else PlayerInk.ink)
			if (state.speed != 1f) {
				GlassPill(speedLabel(state.speed), actions.speed, content = accent)
			} else {
				GlassButton(R.drawable.ic_speed, "Playback speed", actions.speed)
			}
			if (state.canPip) GlassButton(R.drawable.ic_picture_in_picture, "Picture in Picture", actions.pip)
		}
		GlassButton(R.drawable.ic_more_vert, "More", actions.more)
	}
}

/**
 * Skip back, play and pause, skip forward. The play button is the one solid mark on the screen; it is a circle while
 * paused and a softer square while playing, and shows the loader on a glass disc while the picture is buffering.
 */
@Composable
private fun Transport(state: ChromeState, actions: ChromeActions, accent: Color, wide: Boolean, modifier: Modifier) {
	val play = if (wide) 80.dp else 72.dp
	val loading = state.buffering
	val corner by animateDpAsState(
		if (state.playing && !loading) play * 0.35f else play / 2,
		MaterialTheme.motionScheme.fastSpatialSpec(),
		label = "play shape",
	)
	Row(
		modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(if (wide) 48.dp else Space.s7),
	) {
		SkipButton(forward = false, seconds = state.skipSeconds, wide = wide) { actions.skip(-state.skipSeconds) }
		GlassDisc(
			if (state.playing) "Pause" else "Play",
			actions.toggle,
			size = play,
			solid = !loading,
			shape = RoundedCornerShape(corner),
		) {
			if (loading) {
				LoadingIndicator(Modifier.size(play * 0.6f), color = accent)
			} else {
				Crossfade(state.playing, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "play glyph") { playing ->
					Icon(
						painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
						contentDescription = null,
						tint = PlayerInk.onSolid,
						modifier = Modifier.size(play * 0.55f),
					)
				}
			}
		}
		SkipButton(forward = true, seconds = state.skipSeconds, wide = wide) { actions.skip(state.skipSeconds) }
	}
}

/** The skip button: a circular arrow with the number of seconds inside it. */
@Composable
private fun SkipButton(forward: Boolean, seconds: Int, wide: Boolean, onClick: () -> Unit) {
	val disc = if (wide) 64.dp else 56.dp
	val glyph = disc - 24.dp
	GlassDisc(if (forward) "Skip forward $seconds seconds" else "Skip back $seconds seconds", onClick, size = disc) {
		Icon(
			painterResource(if (forward) R.drawable.ic_forward else R.drawable.ic_replay),
			contentDescription = null,
			tint = PlayerInk.ink,
			modifier = Modifier.size(glyph),
		)
		// The arrow's circle is centred a little below the glyph's middle.
		Text(
			"$seconds",
			style = (if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall).copy(fontFeatureSettings = "tnum"),
			color = PlayerInk.ink,
			fontWeight = FontWeight.SemiBold,
			modifier = Modifier.offset(y = glyph / 24).clearAndSetSemantics {},
		)
	}
}

/** Elapsed time, the seek bar, and the time left or the length. */
@Composable
private fun SeekRow(state: ChromeState, actions: ChromeActions, scrub: ScrubState, accent: Color, onBar: (Rect) -> Unit) {
	val shown = if (scrub.active) (scrub.seconds * 1000.0).toLong() else state.positionMs
	Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4), verticalAlignment = Alignment.CenterVertically) {
		Text(
			clock(shown),
			style = clockStyle(),
			color = PlayerInk.ink,
			maxLines = 1,
			modifier = Modifier.defaultMinSize(minWidth = 52.dp).padding(end = Space.s2),
		)
		SeekBar(state, scrub, accent, actions.skip, onBar, Modifier.weight(1f))
		TimeToggle(
			if (state.showsRemaining) "\u2212" + clock((state.durationMs - shown).coerceAtLeast(0L)) else clock(state.durationMs),
			actions.remaining,
		)
	}
}

/** The right-hand time: tapping it swaps the time left for the length. */
@Composable
private fun TimeToggle(text: String, onClick: () -> Unit) {
	val source = remember { MutableInteractionSource() }
	val pressed by source.collectIsPressedAsState()
	Box(
		Modifier
			.defaultMinSize(minWidth = 52.dp, minHeight = Size.target)
			.clickable(
				interactionSource = source,
				indication = null,
				onClickLabel = "Switch between time left and length",
				role = Role.Button,
				onClick = onClick,
			)
			.padding(start = Space.s2),
		contentAlignment = Alignment.CenterEnd,
	) {
		Text(text, style = clockStyle(), color = PlayerInk.ink.copy(alpha = if (pressed) 0.6f else 1f), maxLines = 1)
	}
}

/** The lock at the left; the chapters, the aspect and the next episode at the right. */
@Composable
private fun BottomRow(state: ChromeState, actions: ChromeActions, accent: Color, wide: Boolean, modifier: Modifier) {
	Row(
		modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s2),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		GlassButton(R.drawable.ic_lock, "Lock controls", actions.lock)
		Spacer(Modifier.weight(1f))
		if (state.hasChapters) GlassButton(R.drawable.ic_chapters, "Chapters", actions.chapters)
		if (wide) {
			GlassPill(state.aspect.label, actions.aspect, icon = R.drawable.ic_aspect_ratio)
		} else {
			GlassButton(R.drawable.ic_aspect_ratio, "Aspect: ${state.aspect.label}", actions.aspect)
		}
		if (state.hasNext) {
			if (wide) {
				GlassPill("Next Episode", actions.next, icon = R.drawable.ic_skip_next, fill = accent, content = PlayerInk.onAccent)
			} else {
				GlassButton(R.drawable.ic_skip_next, "Next Episode", actions.next, tint = accent)
			}
		}
	}
}

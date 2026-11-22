package com.nphil.marquee.ui.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nphil.marquee.data.SubtitleEdge
import com.nphil.marquee.data.SubtitleFont
import com.nphil.marquee.data.SubtitleLook

/** How far up the lines move while the controls are over the bottom of the picture, as a share of the screen's height. */
private const val CONTROLS_LIFT = 0.16f

/** The soft shadow: black, see-through, so a line reads on a bright scene without a hard edge. */
private const val SOFT_SHADOW = 0x99000000.toInt()

/**
 * The subtitle lines over the picture, drawn in the person's own [look]: the colours, size and edge come from it, not
 * from the file. Nothing is drawn while [cues] is empty, so no caption stays on the panel between lines.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun CaptionView(cues: List<Cue>, look: SubtitleLook, controlsUp: Boolean, modifier: Modifier = Modifier) {
	val style = remember(look) { look.captionStyle() }
	val lift by animateFloatAsState(if (controlsUp) CONTROLS_LIFT else 0f, label = "caption lift")
	AndroidView(
		factory = { context -> SubtitleView(context).apply { setApplyEmbeddedFontSizes(false) } },
		update = { view ->
			view.setStyle(style)
			view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * look.size / 100f)
			view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION + lift)
			view.setCues(cues)
		},
		modifier = modifier,
	)
}

@OptIn(UnstableApi::class)
internal fun SubtitleLook.captionStyle(): CaptionStyleCompat {
	val edgeType = when (edge) {
		SubtitleEdge.None -> CaptionStyleCompat.EDGE_TYPE_NONE
		SubtitleEdge.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
		SubtitleEdge.DropShadow, SubtitleEdge.SoftShadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
	}
	val edgeColour = if (edge == SubtitleEdge.SoftShadow) SOFT_SHADOW else Color.BLACK
	return CaptionStyleCompat(textArgb, boxArgb, Color.TRANSPARENT, edgeType, edgeColour, typeface())
}

private fun SubtitleLook.typeface(): Typeface {
	val weight = if (bold) Typeface.BOLD else Typeface.NORMAL
	return when (font) {
		SubtitleFont.Standard -> Typeface.create(Typeface.SANS_SERIF, weight)
		SubtitleFont.Mono -> Typeface.create(Typeface.MONOSPACE, weight)
		SubtitleFont.Legible -> Typeface.create("sans-serif-medium", weight)
	}
}

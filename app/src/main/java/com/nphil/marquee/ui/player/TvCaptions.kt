package com.nphil.marquee.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.nphil.marquee.data.SubtitleLook

private fun Cue.hasPlacement() = bitmap != null || position != Cue.DIMEN_UNSET || line != Cue.DIMEN_UNSET || verticalType != Cue.TYPE_UNSET

/** Moving ordinary dialogue away from the TV transport must not move a sign over a different object. */
@Composable
internal fun TvCaptions(cues: List<Cue>, look: SubtitleLook, controlsUp: Boolean, modifier: Modifier = Modifier) {
	if (cues.isEmpty()) return
	val (placed, dialogue) = remember(cues) {
		when {
			cues.none { it.hasPlacement() } -> emptyList<Cue>() to cues
			cues.all { it.hasPlacement() } -> cues to emptyList()
			else -> cues.partition { it.hasPlacement() }
		}
	}
	if (placed.isEmpty()) {
		CaptionView(dialogue, look, controlsUp, modifier)
		return
	}
	Box(modifier) {
		if (dialogue.isNotEmpty()) CaptionView(dialogue, look, controlsUp, Modifier.fillMaxSize())
		// Canvas flattens vertical writing. Keep horizontal signs on Canvas even while a vertical cue is on screen.
		val (upright, canvas) = remember(placed) {
			when {
				placed.none { it.verticalType != Cue.TYPE_UNSET } -> emptyList<Cue>() to placed
				placed.all { it.verticalType != Cue.TYPE_UNSET } -> placed to emptyList()
				else -> placed.partition { it.verticalType != Cue.TYPE_UNSET }
			}
		}
		val style = remember(look) { look.captionStyle() }
		if (canvas.isNotEmpty()) AndroidView(
			factory = { SubtitleView(it) },
			update = { it.setStyle(style); it.setCues(canvas) },
			modifier = Modifier.fillMaxSize(),
		)
		if (upright.isNotEmpty()) AndroidView(
			factory = { SubtitleView(it).apply {
				descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
				setViewType(SubtitleView.VIEW_TYPE_WEB)
			} },
			update = { it.setStyle(style); it.setCues(upright) },
			onRelease = { it.setViewType(SubtitleView.VIEW_TYPE_CANVAS) },
			modifier = Modifier.fillMaxSize(),
		)
	}
}

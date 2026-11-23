package com.nphil.marquee.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.nphil.marquee.diagnostics.ScrollMonitor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Tells [ScrollMonitor] while [state] moves: "interacting" while a finger drags it, "decelerating" for the fling or
 * animated scroll after. It reads nothing in composition, so tracking a surface recomposes nothing; two flows feed it.
 * [surface] is the name the gesture goes by in the log, such as the host's file name. Pass the scroll view's own
 * [interactions] (`state.interactionSource` on a lazy list or grid).
 */
@Composable
internal fun TrackScroll(surface: String, state: ScrollableState, interactions: InteractionSource) {
	val window = LocalActivity.current?.window ?: return
	LaunchedEffect(surface, state, interactions, window) {
		var dragging = false
		var scrolling = false
		fun report() = ScrollMonitor.moving(window, scrolling, surface, if (dragging) "interacting" else "decelerating")
		try {
			coroutineScope {
				launch {
					interactions.interactions.collect {
						when (it) {
							is DragInteraction.Start -> dragging = true
							is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
						}
						report()
					}
				}
				snapshotFlow { state.isScrollInProgress }.collect {
					scrolling = it
					report()
				}
			}
		} finally {
			ScrollMonitor.moving(window, false, surface, "idle")
		}
	}
}

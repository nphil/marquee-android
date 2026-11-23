package com.nphil.marquee.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Where the tablet's sidebar and its profile button are, for whatever floats over the shell. Read these only inside
 * layout and draw lambdas (`Modifier.layout`, `offset { }`, `graphicsLayer { }`): a drag or the toggle then moves the
 * reader in the same frame, and nothing recomposes.
 */
@Stable
class ShellMetrics {
	private var sidebar by mutableStateOf<SidebarSize?>(null)

	/**
	 * The sidebar's width in px from the window's start edge, its start inset included. It is live while the sidebar is
	 * dragged or animates, and 0 while there is no sidebar: on a phone, or while a page covers the tabs.
	 */
	val sidebarWidth: Float get() = sidebar?.widthPx ?: 0f

	/**
	 * The Settings avatar's slot in window px. It keeps its place while the sidebar changes width. Null while there is
	 * no sidebar.
	 */
	var profileBounds: Rect? by mutableStateOf(null)
		internal set

	/** The height in px the sidebar's foot keeps free directly above the Settings row. The floating remote sets it. */
	var dockRoom: Float by mutableFloatStateOf(0f)

	internal fun attach(size: SidebarSize) {
		sidebar = size
	}

	internal fun detach(size: SidebarSize) {
		if (sidebar === size) {
			sidebar = null
			profileBounds = null
		}
	}
}

/** One [ShellMetrics] for the app, made in `MarqueeApp`. */
val LocalShellMetrics = staticCompositionLocalOf { ShellMetrics() }

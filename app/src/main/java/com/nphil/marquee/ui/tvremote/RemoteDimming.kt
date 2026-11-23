package com.nphil.marquee.ui.tvremote

/** How long an untouched orb may sit at full brightness over app content before it dims. */
internal const val DimDwellMs = 4_000L

/** The orb's alpha once dimmed. */
internal const val DimmedAlpha = 0.4f

/**
 * Whether the orb dims: only while it floats at an edge with the panel closed, because a bright element that stays in
 * one place wears the OLED panel. The sidebar-docked orb follows the sidebar's own chrome and never dims.
 */
internal fun orbDimmed(dock: RemoteDock, panelOpen: Boolean, idleMs: Long): Boolean =
	dock is RemoteDock.Edge && !panelOpen && idleMs >= DimDwellMs

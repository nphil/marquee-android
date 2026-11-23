package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

internal enum class DockSide { Start, End }

/** Where the orb rests: beside the profile button in the sidebar footer, or on one of the screen's two edges. */
internal sealed interface RemoteDock {
	data object Sidebar : RemoteDock
	data class Edge(val side: DockSide, val row: Int) : RemoteDock
}

internal const val DockRows = 5

internal val DefaultEdge = RemoteDock.Edge(DockSide.End, 3)

internal fun RemoteDock.encoded(): String = when (this) {
	RemoteDock.Sidebar -> "sidebar"
	is RemoteDock.Edge -> "${if (side == DockSide.Start) "start" else "end"}:$row"
}

internal fun decodedDock(text: String?): RemoteDock? {
	if (text == "sidebar") return RemoteDock.Sidebar
	val parts = text?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
	val side = when (parts[0]) {
		"start" -> DockSide.Start
		"end" -> DockSide.End
		else -> return null
	}
	return parts[1].toIntOrNull()?.takeIf { it in 0 until DockRows }?.let { RemoteDock.Edge(side, it) }
}

/** Top-left corner of an edge spot. [field] is the area the whole orb may occupy. */
internal fun edgeOrigin(field: Rect, dock: RemoteDock.Edge, size: Float): Offset {
	val x = if (dock.side == DockSide.Start) field.left else field.right - size
	val y = field.top + (field.height - size) * dock.row / (DockRows - 1)
	return Offset(x, y)
}

/** Where a fling would bring the orb to rest. */
internal fun projectedCenter(center: Offset, velocity: Offset, seconds: Float = 0.18f): Offset = center + velocity * seconds

/**
 * The dock closest to [center]. The sidebar spot, when there is one, captures anything within [capture] of
 * [sidebar] so the orb does not have to be dropped exactly on it.
 */
internal fun nearestDock(center: Offset, field: Rect, size: Float, sidebar: Offset?, capture: Float): RemoteDock {
	if (sidebar != null && (center - sidebar).getDistance() <= capture) return RemoteDock.Sidebar
	val side = if (center.x < field.center.x) DockSide.Start else DockSide.End
	val span = field.height - size
	val row = if (span <= 0f) 0 else ((center.y - size / 2 - field.top) / span * (DockRows - 1)).roundToInt().coerceIn(0, DockRows - 1)
	return RemoteDock.Edge(side, row)
}

/**
 * Top-left of the orb in the sidebar footer. [blend] 1 is inline: right-aligned in the profile row, centred on the
 * avatar's line. [blend] 0 is stacked in its own line directly above the avatar, for a sidebar too narrow to hold both.
 */
internal fun sidebarOrigin(sidebarWidth: Float, profile: Rect, size: Float, inset: Float, gap: Float, blend: Float): Offset {
	val inline = Offset(sidebarWidth - inset - size, profile.center.y - size / 2)
	val stacked = Offset(profile.center.x - size / 2, profile.top - gap - size)
	return stacked + (inline - stacked) * blend
}

/** The footer height the sidebar keeps free above the profile button while the orb is stacked. */
internal fun dockRoom(blend: Float, room: Float): Float = (1f - blend) * room

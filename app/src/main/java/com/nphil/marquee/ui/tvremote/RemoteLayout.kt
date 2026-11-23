package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** The groups of controls the panel can show. [Pad] is always on; the rest are the user's choice. */
internal enum class RemoteModule(val id: String, val label: String) {
	Pad("pad", "Pad"),
	Nav("nav", "Back & home"),
	Volume("volume", "Volume"),
	Channel("channel", "Channel"),
	Playback("playback", "Playback"),
	Keyboard("keyboard", "Keyboard"),
	Apps("apps", "Apps"),
	More("more", "More"),
}

/** A row of keys under or beside the pad. Declaration order is the order on the panel. */
internal enum class RemoteStrip(val modules: List<RemoteModule>) {
	Nav(listOf(RemoteModule.Nav)),
	Volume(listOf(RemoteModule.Volume)),
	Channel(listOf(RemoteModule.Channel)),
	Playback(listOf(RemoteModule.Playback)),
	Launchers(listOf(RemoteModule.Keyboard, RemoteModule.Apps, RemoteModule.More)),
}

internal enum class RemoteShape { Column, Row, Compact }

/** All measures are dp on a 4 grid, relative to the panel's top-left corner. */
internal object RemoteMetrics {
	const val Grid = 4f
	const val Margin = 16f
	const val Gap = 12f
	const val Key = 56f
	const val Header = 56f
	const val PadMin = 144f
	const val StripWidth = 192f
	const val Tray = 56f
	const val MinPanel = 232f
	const val SwitchRow = 48f
}

internal class StripPlacement(val strip: RemoteStrip, val rect: Rect, val modules: List<RemoteModule>) {
	/** A strip is three keys; the launchers are one key per module the user kept. */
	val keys: Int get() = if (strip == RemoteStrip.Launchers) modules.size else 3

	val keyRects: List<Rect> get() = rect.split(keys, RemoteMetrics.Gap)

	/** What a tap on the edit badge removes, with the rectangle it outlines. */
	val moduleRects: List<Pair<RemoteModule, Rect>>
		get() = if (strip == RemoteStrip.Launchers) modules.zip(keyRects) else listOf(modules.first() to rect)
}

internal class RemoteLayout(
	val shape: RemoteShape,
	val area: Rect,
	val pad: Rect,
	val strips: List<StripPlacement>,
	/** Visible modules that did not fit at this size. */
	val dropped: List<RemoteModule>,
)

/** The strip that goes first when space runs out: the least used keys leave before the essentials. */
private val DropOrder = listOf(RemoteStrip.Channel, RemoteStrip.Playback, RemoteStrip.Launchers, RemoteStrip.Volume, RemoteStrip.Nav)

private fun grid(value: Float) = floor(value / RemoteMetrics.Grid) * RemoteMetrics.Grid

private fun MutableList<RemoteStrip>.dropOne() = remove(DropOrder.first { it in this })

/**
 * Lays the panel out for its shape: a column when it is tall, a row when it is wide, and the pad alone when it is
 * small. Strips that do not fit are dropped in [DropOrder] and reported in [RemoteLayout.dropped]. [tray] reserves
 * room along the bottom for the hidden-module chips while editing.
 */
internal fun remoteLayout(width: Float, height: Float, visible: Set<RemoteModule>, tray: Float = 0f): RemoteLayout {
	val m = RemoteMetrics
	val w = grid(max(width, m.MinPanel))
	val h = grid(max(height, m.MinPanel))
	val area = Rect(m.Margin, m.Header, w - m.Margin, h - m.Margin - tray)
	val wanted = RemoteStrip.entries.filter { strip -> strip.modules.any { it in visible } }
	val shape = when {
		area.width >= 1.25f * area.height && area.width >= m.PadMin + m.Gap + m.StripWidth -> RemoteShape.Row
		area.height >= m.PadMin + m.Gap + m.Key && area.width >= m.StripWidth -> RemoteShape.Column
		else -> RemoteShape.Compact
	}
	return when (shape) {
		RemoteShape.Compact -> RemoteLayout(shape, area, area, emptyList(), droppedBy(emptyList(), visible))
		RemoteShape.Column -> columnLayout(area, wanted, visible)
		RemoteShape.Row -> rowLayout(area, wanted, visible)
	}
}

private fun columnLayout(area: Rect, wanted: List<RemoteStrip>, visible: Set<RemoteModule>): RemoteLayout {
	val m = RemoteMetrics
	val shown = wanted.toMutableList()
	// The strips plus the gap above them.
	fun need(count: Int) = if (count == 0) 0f else count * m.Key + (count - 1) * m.Gap + m.Gap
	while (shown.isNotEmpty() && area.height - need(shown.size) < m.PadMin) shown.dropOne()
	val pad = Rect(area.left, area.top, area.right, area.top + area.height - need(shown.size))
	var y = pad.bottom + m.Gap
	val strips = shown.map { strip ->
		StripPlacement(strip, Rect(area.left, y, area.right, y + m.Key), strip.modules.filter { it in visible }).also { y += m.Key + m.Gap }
	}
	return RemoteLayout(RemoteShape.Column, area, pad, strips, droppedBy(strips, visible))
}

private fun rowLayout(area: Rect, wanted: List<RemoteStrip>, visible: Set<RemoteModule>): RemoteLayout {
	val m = RemoteMetrics
	val capacity = max(floor((area.height + m.Gap) / (m.Key + m.Gap)).toInt(), 0)
	val columns = if (area.width >= m.PadMin + 2 * (m.StripWidth + m.Gap)) 2 else 1
	val shown = wanted.toMutableList()
	while (shown.size > capacity * columns) shown.dropOne()
	val used = if (shown.isEmpty()) 0 else ceil(shown.size / capacity.toFloat()).toInt()
	val pad = Rect(area.left, area.top, area.left + area.width - used * (m.StripWidth + m.Gap), area.bottom)
	val perColumn = if (used == 0) 1 else ceil(shown.size / used.toFloat()).toInt()
	val strips = shown.chunked(perColumn).flatMapIndexed { column, group ->
		val span = group.size * m.Key + (group.size - 1) * m.Gap
		val x = pad.right + m.Gap + column * (m.StripWidth + m.Gap)
		var y = area.top + grid((area.height - span) / 2)
		group.map { strip ->
			StripPlacement(strip, Rect(x, y, x + m.StripWidth, y + m.Key), strip.modules.filter { it in visible }).also { y += m.Key + m.Gap }
		}
	}
	return RemoteLayout(RemoteShape.Row, area, pad, strips, droppedBy(strips, visible))
}

private fun droppedBy(strips: List<StripPlacement>, visible: Set<RemoteModule>): List<RemoteModule> {
	val placed = strips.flatMap { it.modules }.toSet()
	return RemoteModule.entries.filter { it != RemoteModule.Pad && it in visible && it !in placed }
}

internal fun Rect.split(count: Int, gap: Float): List<Rect> {
	if (count <= 0) return emptyList()
	val each = (width - gap * (count - 1)) / count
	return List(count) { index ->
		val start = left + index * (each + gap)
		Rect(start, top, start + each, bottom)
	}
}

internal val AllModules: Set<RemoteModule> = RemoteModule.entries.toSet()

internal fun encodeModules(modules: Set<RemoteModule>): String =
	RemoteModule.entries.filter { it != RemoteModule.Pad && it in modules }.joinToString(",") { it.id }

/** An unsaved choice shows everything; a saved empty one is a panel with only the pad. */
internal fun decodeModules(text: String?): Set<RemoteModule> {
	if (text == null) return AllModules
	val chosen = text.split(',').mapNotNull { id -> RemoteModule.entries.firstOrNull { it.id == id } }
	return (chosen + RemoteModule.Pad).toSet()
}

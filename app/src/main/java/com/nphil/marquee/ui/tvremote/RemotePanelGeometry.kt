package com.nphil.marquee.ui.tvremote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The panel's rectangle as fractions of the window, so a saved layout survives rotation and a new screen. */
internal data class PanelFractions(val x: Float, val y: Float, val w: Float, val h: Float) {
	fun toRect(window: Rect) = Rect(
		window.left + x * window.width, window.top + y * window.height,
		window.left + (x + w) * window.width, window.top + (y + h) * window.height,
	)

	fun encoded() = "%.4f,%.4f,%.4f,%.4f".format(Locale.ROOT, x, y, w, h)
}

internal fun Rect.toFractions(window: Rect) = PanelFractions(
	(left - window.left) / window.width, (top - window.top) / window.height, width / window.width, height / window.height,
)

internal fun decodedPanel(text: String?): PanelFractions? {
	val parts = text?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 } ?: return null
	return PanelFractions(parts[0], parts[1], parts[2], parts[3]).takeIf { it.w > 0f && it.h > 0f }
}

/** Which edges a resize handle moves. */
internal data class PanelHandle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
	companion object {
		val All = listOf(
			PanelHandle(left = true, top = true), PanelHandle(top = true), PanelHandle(right = true, top = true),
			PanelHandle(left = true), PanelHandle(right = true),
			PanelHandle(left = true, bottom = true), PanelHandle(bottom = true), PanelHandle(right = true, bottom = true),
		)
	}
}

/** The size and place the panel opens at before the user has moved it: centred across [bounds], at its top. */
internal fun defaultPanel(bounds: Rect, width: Float, height: Float): Rect {
	val w = min(width, bounds.width)
	val h = min(height, bounds.height)
	return Rect(Offset(bounds.center.x - w / 2, bounds.top), Size(w, h))
}

/** Keeps [rect] inside [bounds], shrinking it only when it cannot fit even at [minSize]. */
internal fun fitPanel(rect: Rect, bounds: Rect, minSize: Float): Rect {
	val w = rect.width.coerceIn(min(minSize, bounds.width), bounds.width)
	val h = rect.height.coerceIn(min(minSize, bounds.height), bounds.height)
	val x = rect.left.coerceIn(bounds.left, bounds.right - w)
	val y = rect.top.coerceIn(bounds.top, bounds.bottom - h)
	return Rect(x, y, x + w, y + h)
}

internal fun movePanel(rect: Rect, delta: Offset, bounds: Rect): Rect = fitPanel(rect.translate(delta), bounds, min(rect.width, rect.height))

private fun Float.between(low: Float, high: Float) = coerceIn(low, max(low, high))

internal fun resizePanel(rect: Rect, handle: PanelHandle, delta: Offset, bounds: Rect, minSize: Float): Rect {
	var left = rect.left
	var top = rect.top
	var right = rect.right
	var bottom = rect.bottom
	val minW = min(minSize, bounds.width)
	val minH = min(minSize, bounds.height)
	if (handle.left) left = (left + delta.x).between(bounds.left, right - minW)
	if (handle.right) right = (right + delta.x).between(left + minW, bounds.right)
	if (handle.top) top = (top + delta.y).between(bounds.top, bottom - minH)
	if (handle.bottom) bottom = (bottom + delta.y).between(top + minH, bounds.bottom)
	return Rect(left, top, right, bottom)
}

/** Rounds every edge to the grid so the panel's measures stay on it. */
internal fun snapPanel(rect: Rect, step: Float): Rect {
	fun snap(value: Float) = (value / step).roundToInt() * step
	val left = snap(rect.left)
	val top = snap(rect.top)
	return Rect(left, top, max(snap(rect.right), left + step), max(snap(rect.bottom), top + step))
}

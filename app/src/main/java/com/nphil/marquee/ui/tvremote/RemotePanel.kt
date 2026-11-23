package com.nphil.marquee.ui.tvremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.tvremote.SavedTv
import com.nphil.marquee.tvremote.TvStatus
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** Lucent's long-press discovery time: a touch that rests this long on an empty area starts arranging. */
private const val HoldMillis = 600L

/** What the panel asks of its host: move or resize, then settle the result onto the grid and save it. */
internal class PanelMotion(
	val move: (Offset) -> Unit,
	val resize: (PanelHandle, Offset) -> Unit,
	val settle: () -> Unit,
)

/**
 * The panel's face: a header over the controls on the face, or over a scrolling page for pairing, lists and the keyboard.
 * Everything is laid out at [size], which is the panel's final size whatever the container is animating to.
 */
@Composable
internal fun RemotePanelFrame(
	chrome: RemoteChrome, content: @Composable () -> Unit, size: DpSize, modules: Set<RemoteModule>,
	onModules: (Set<RemoteModule>) -> Unit, mode: PadMode, onMode: (PadMode) -> Unit, editing: Boolean,
	onEditing: (Boolean) -> Unit, onReset: () -> Unit, backEnabled: Boolean, input: (RemoteRequest) -> Unit, motion: PanelMotion,
) {
	BackHandler(enabled = backEnabled) { if (editing) onEditing(false) else chrome.onBack() }
	LaunchedEffect(chrome.face) { if (!chrome.face) onEditing(false) }
	CompositionLocalProvider(LocalContentColor provides lucent.ink) {
		if (chrome.face) {
			FacePanel(chrome, size, modules, onModules, mode, onMode, editing, onEditing, onReset, input, motion)
		} else {
			PagePanel(chrome, content, motion)
		}
	}
}

@Composable
private fun PagePanel(chrome: RemoteChrome, content: @Composable () -> Unit, motion: PanelMotion) {
	Column(Modifier.fillMaxSize()) {
		HeaderRow(motion, movable = true) {
			Text(chrome.title, Modifier.weight(1f).padding(start = Space.s2), style = MaterialTheme.typography.titleMedium,
				maxLines = 1, overflow = TextOverflow.Ellipsis)
			RemoteGlyph(if (chrome.backIcon) "Back" else "Close remote",
				if (chrome.backIcon) R.drawable.ic_arrow_back else R.drawable.ic_close, enabled = !chrome.busy, onClick = chrome.onBack)
		}
		Column(
			Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
			verticalArrangement = Arrangement.spacedBy(Space.s3),
		) {
			chrome.notice?.let { Notice(it) }
			content()
		}
	}
}

@Composable
private fun FacePanel(
	chrome: RemoteChrome, size: DpSize, modules: Set<RemoteModule>, onModules: (Set<RemoteModule>) -> Unit, mode: PadMode,
	onMode: (PadMode) -> Unit, editing: Boolean, onEditing: (Boolean) -> Unit, onReset: () -> Unit,
	input: (RemoteRequest) -> Unit, motion: PanelMotion,
) {
	val haptics = LocalHapticFeedback.current
	val hidden = RemoteModule.entries.filter { it != RemoteModule.Pad && it !in modules }
	val plain = remoteLayout(size.width.value, size.height.value, modules)
	// The tray costs a strip of height, so it exists only while there is something to put in it.
	val tray = editing && (hidden.isNotEmpty() || plain.dropped.isNotEmpty())
	val layout = if (tray) remoteLayout(size.width.value, size.height.value, modules, RemoteMetrics.Tray) else plain
	Box(
		Modifier.fillMaxSize().then(
			if (editing) Modifier else Modifier.onHold(HoldMillis) {
				haptics.performHapticFeedback(HapticFeedbackType.LongPress)
				onEditing(true)
			},
		),
	) {
		RemoteFaceBody(
			layout, chrome.status, mode, onMode, input, chrome.actions, editing, onRemove = { onModules(modules - it) },
			modifier = Modifier.fillMaxSize(), editSurface = Modifier.rootDrag(onDrag = motion.move, onEnd = { motion.settle() }),
			handles = { ResizeHandles(size, motion) },
		)
		if (tray) {
			ModuleTray(hidden, layout.dropped, Rect(layout.area.left, layout.area.bottom, layout.area.right, layout.area.bottom + RemoteMetrics.Tray),
				onAdd = { onModules(modules + it) }, onRemove = { onModules(modules - it) })
		}
		if (editing) {
			EditHeader(motion, onReset) { onEditing(false) }
		} else {
			FaceHeader(chrome, motion) { onEditing(true) }
			chrome.notice?.let { Box(Modifier.offset(y = RemoteMetrics.Header.dp).padding(horizontal = Space.s4)) { Notice(it) } }
		}
	}
}

/** The header band. A drag on it moves the panel; TalkBack gets the same moves as actions. */
@Composable
private fun HeaderRow(motion: PanelMotion, movable: Boolean, content: @Composable RowScope.() -> Unit) {
	val step = with(LocalDensity.current) { 48.dp.toPx() }
	fun nudged(delta: Offset): Boolean {
		motion.move(delta)
		motion.settle()
		return true
	}
	Row(
		Modifier.fillMaxWidth().height(RemoteMetrics.Header.dp)
			.then(if (movable) Modifier.rootDrag(onDrag = motion.move, onEnd = { motion.settle() }) else Modifier)
			.semantics {
				if (movable) {
					customActions = listOf(
						CustomAccessibilityAction("Move left") { nudged(Offset(-step, 0f)) },
						CustomAccessibilityAction("Move right") { nudged(Offset(step, 0f)) },
						CustomAccessibilityAction("Move up") { nudged(Offset(0f, -step)) },
						CustomAccessibilityAction("Move down") { nudged(Offset(0f, step)) },
					)
				}
			}
			.padding(horizontal = Space.s2),
		verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1), content = content,
	)
}

@Composable
private fun FaceHeader(chrome: RemoteChrome, motion: PanelMotion, onEdit: () -> Unit) {
	val tv = chrome.tv
	val status = chrome.status
	HeaderRow(motion, movable = true) {
		// The name and state double as the TV chooser, which keeps the header to three buttons.
		Column(
			Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Choose TV", onClick = chrome.actions.onTvs)
				.padding(start = Space.s2).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
		) {
			Text(tv?.name ?: "LumaTV", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(statusText(tv, status), color = if (status?.ready == true) lucent.accentText else lucent.ink2,
				style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		RemoteGlyph(if (status?.status == "connected" && !status.standby) "TV power" else "Wake TV", R.drawable.ic_power,
			enabled = tv != null && status?.status != "unpaired", emphasized = true, onClick = chrome.actions.onPower)
		RemoteGlyph("Arrange", R.drawable.ic_tune, onClick = onEdit)
		RemoteGlyph("Close remote", R.drawable.ic_close, enabled = !chrome.busy, onClick = chrome.onBack)
	}
}

/**
 * The buttons sit in the middle, clear of the corner handles, and the band itself takes no touches so the handles
 * and the move drag underneath it still work.
 */
@Composable
private fun EditHeader(motion: PanelMotion, onReset: () -> Unit, onDone: () -> Unit) {
	val step = with(LocalDensity.current) { 48.dp.toPx() }
	fun grown(handle: PanelHandle, delta: Offset): Boolean {
		motion.resize(handle, delta)
		motion.settle()
		return true
	}
	val right = PanelHandle(right = true)
	val bottom = PanelHandle(bottom = true)
	Row(
		Modifier.fillMaxWidth().height(RemoteMetrics.Header.dp).semantics {
			customActions = listOf(
				CustomAccessibilityAction("Wider") { grown(right, Offset(step, 0f)) },
				CustomAccessibilityAction("Narrower") { grown(right, Offset(-step, 0f)) },
				CustomAccessibilityAction("Taller") { grown(bottom, Offset(0f, step)) },
				CustomAccessibilityAction("Shorter") { grown(bottom, Offset(0f, -step)) },
			)
		},
		verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1),
	) {
		Spacer(Modifier.weight(1f))
		RemoteGlyph("Reset layout", R.drawable.ic_restart_alt, onClick = onReset)
		RemoteGlyph("Done", R.drawable.ic_check, emphasized = true, onClick = onDone)
		Spacer(Modifier.weight(1f))
	}
}

@Composable
private fun Notice(text: String) {
	Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = lucent.danger, style = MaterialTheme.typography.bodyMedium)
}

private fun statusText(tv: SavedTv?, status: TvStatus?) = when (status?.status) {
	"connected" -> if (status.standby) "Standby" else "Connected"
	"offline" -> "Offline · reconnecting"
	"incompatible" -> "TV needs a compatible version"
	"unpaired" -> "Pair again"
	else -> if (tv != null) "Connecting…" else "No TV paired"
}

/**
 * Eight places to take hold of the panel's edge while arranging: a 48 dp zone at each corner and a 32 dp one along each
 * side, drawn as brackets and pills inside the edge so they read as part of the panel.
 */
@Composable
private fun ResizeHandles(size: DpSize, motion: PanelMotion) {
	val haptics = LocalHapticFeedback.current
	val width = size.width.value
	val height = size.height.value
	for (handle in PanelHandle.All) {
		val corner = (handle.left || handle.right) && (handle.top || handle.bottom)
		val rect = when {
			corner -> {
				val x = if (handle.left) 0f else width - 48f
				val y = if (handle.top) 0f else height - 48f
				Rect(x, y, x + 48f, y + 48f)
			}
			handle.top || handle.bottom -> {
				val y = if (handle.top) 0f else height - 32f
				Rect(48f, y, width - 48f, y + 32f)
			}
			else -> {
				val x = if (handle.left) 0f else width - 32f
				Rect(x, 48f, x + 32f, height - 48f)
			}
		}
		val color = lucent.accentText
		Box(
			Modifier.rect(rect)
				.rootDrag(onStart = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }, onDrag = { motion.resize(handle, it) },
					onEnd = { motion.settle() })
				.drawBehind { drawHandle(handle, corner, color) },
		)
	}
}

private fun DrawScope.drawHandle(handle: PanelHandle, corner: Boolean, color: Color) {
	val inset = 6.dp.toPx()
	val thick = 3.dp.toPx()
	if (corner) {
		val arm = 16.dp.toPx()
		val x = if (handle.left) inset else size.width - inset
		val y = if (handle.top) inset else size.height - inset
		val dx = if (handle.left) arm else -arm
		val dy = if (handle.top) arm else -arm
		drawLine(color, Offset(x, y), Offset(x + dx, y), thick, StrokeCap.Round)
		drawLine(color, Offset(x, y), Offset(x, y + dy), thick, StrokeCap.Round)
		return
	}
	val long = 32.dp.toPx()
	if (handle.top || handle.bottom) {
		val y = if (handle.top) inset else size.height - inset - thick
		drawRoundRect(color, Offset((size.width - long) / 2, y), Size(long, thick), CornerRadius(thick / 2))
	} else {
		val x = if (handle.left) inset else size.width - inset - thick
		drawRoundRect(color, Offset(x, (size.height - long) / 2), Size(thick, long), CornerRadius(thick / 2))
	}
}

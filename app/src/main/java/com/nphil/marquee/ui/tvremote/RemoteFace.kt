package com.nphil.marquee.ui.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteRequest
import com.nphil.marquee.tvremote.TvStatus
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** What the face's keys and header do beyond sending a TV key. */
internal class RemoteFaceActions(
	val onTvs: () -> Unit,
	val onPair: () -> Unit,
	val onPower: () -> Unit,
	val onReconnect: () -> Unit,
	val onKeyboard: () -> Unit,
	val onApps: () -> Unit,
	val onMore: () -> Unit,
)

/** Places a child at a layout rectangle, which is in dp from the panel's corner. */
internal fun Modifier.rect(rect: Rect): Modifier = offset(rect.left.dp, rect.top.dp).size(rect.width.dp, rect.height.dp)

/**
 * The remote's controls, each at the rectangle [layout] gives it. In [editing] they are inert and outlined; [handles]
 * sits above the touch-swallowing overlay and below the outlines, so a badge wins where it meets a handle.
 */
@Composable
internal fun RemoteFaceBody(
	layout: RemoteLayout, status: TvStatus?, mode: PadMode, onMode: (PadMode) -> Unit, input: (RemoteRequest) -> Unit,
	actions: RemoteFaceActions, editing: Boolean, onRemove: (RemoteModule) -> Unit, modifier: Modifier = Modifier,
	editSurface: Modifier = Modifier, handles: @Composable () -> Unit = {},
) {
	val ready = status?.ready == true
	Box(modifier) {
		PadRegion(status, mode, onMode, input, actions, editing, Modifier.rect(layout.pad))
		for (placement in layout.strips) StripKeys(placement, status, ready, input, actions)
		if (editing) {
			// Swallows touches so keys do not fire while arranging, and carries the drag that moves the panel.
			Box(Modifier.fillMaxSize().then(editSurface).pointerInput(Unit) {})
			handles()
			Outline(layout.pad, null)
			for (placement in layout.strips) for ((module, rect) in placement.moduleRects) Outline(rect, module) { onRemove(module) }
		}
	}
}

/** Why the pad does not answer and the one thing that fixes it. Null while the pad answers. */
private class PadNotice(val text: String, val action: String?, val perform: () -> Unit = {}, val emphasized: Boolean = false)

private fun padNotice(status: TvStatus?, actions: RemoteFaceActions): PadNotice? = when {
	status == null -> null
	status.status == "offline" -> PadNotice("The TV can’t be reached.", "Reconnect", actions.onReconnect)
	status.status == "unpaired" -> PadNotice("This phone is no longer paired with the TV.", "Pair again", actions.onPair, emphasized = true)
	status.status == "incompatible" -> PadNotice("The TV needs a compatible version.", null)
	status.status == "connected" && status.standby -> PadNotice("The TV is in standby.", "Wake TV", actions.onPower, emphasized = true)
	else -> null
}

/** The pad's region: the pad and its switch, or, while the pad does not answer, the reason and its one button, alone and centred. */
@Composable
private fun PadRegion(
	status: TvStatus?, mode: PadMode, onMode: (PadMode) -> Unit, input: (RemoteRequest) -> Unit, actions: RemoteFaceActions,
	editing: Boolean, modifier: Modifier,
) {
	val notice = if (editing) null else padNotice(status, actions)
	if (notice == null) {
		RemotePad(mode, onMode, status?.ready == true, status == null || status.pointer, input, modifier)
		return
	}
	Column(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically)) {
		Text(notice.text, color = lucent.ink2, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
		notice.action?.let { RemoteButton(it, onClick = notice.perform, emphasized = notice.emphasized) }
	}
}

@Composable
private fun StripKeys(placement: StripPlacement, status: TvStatus?, ready: Boolean, input: (RemoteRequest) -> Unit, actions: RemoteFaceActions) {
	val rects = placement.keyRects
	when (placement.strip) {
		RemoteStrip.Nav -> {
			RemoteKey("back", "TV Back", R.drawable.ic_arrow_back, ready, input, Modifier.rect(rects[0]), hold = true)
			RemoteKey("home", "TV Home", R.drawable.ic_home, ready, input, Modifier.rect(rects[1]))
			RemoteKey("menu", "TV Settings", R.drawable.ic_settings, ready, input, Modifier.rect(rects[2]), hold = true)
		}
		RemoteStrip.Volume -> {
			val muted = status?.muted == true
			val level = status?.volume?.takeIf { ready && !muted }?.displayLevel()
			RemoteKey("voldown", "Volume down", R.drawable.ic_volume_down, ready, input, Modifier.rect(rects[0]), repeat = true)
			RemoteKey("mute", if (muted) "Unmute TV" else "Mute TV", R.drawable.ic_volume_off, ready, input, Modifier.rect(rects[1]),
				emphasized = muted, text = level)
			RemoteKey("volup", "Volume up", R.drawable.ic_volume_up, ready, input, Modifier.rect(rects[2]), repeat = true)
		}
		RemoteStrip.Channel -> {
			RemoteKey("chdown", "Channel down", R.drawable.ic_expand_more, ready, input, Modifier.rect(rects[0]), repeat = true)
			RemoteKey("guide", "Guide", R.drawable.ic_calendar_month, ready, input, Modifier.rect(rects[1]))
			RemoteKey("chup", "Channel up", R.drawable.ic_expand_more, ready, input, Modifier.rect(rects[2]), turn = 180f, repeat = true)
		}
		RemoteStrip.Playback -> {
			RemoteKey("rew", "Rewind", R.drawable.ic_fast_forward, ready, input, Modifier.rect(rects[0]), turn = 180f, repeat = true)
			RemoteKey("playpause", "Play or pause TV", R.drawable.ic_play_arrow, ready, input, Modifier.rect(rects[1]))
			RemoteKey("ff", "Fast forward", R.drawable.ic_fast_forward, ready, input, Modifier.rect(rects[2]), repeat = true)
		}
		RemoteStrip.Launchers -> for ((module, rect) in placement.moduleRects) {
			when (module) {
				RemoteModule.Keyboard -> KeyCap("TV keyboard", R.drawable.ic_keyboard, Modifier.rect(rect),
					enabled = ready && status?.keyboard == true, onClick = actions.onKeyboard)
				RemoteModule.Apps -> KeyCap("Apps", R.drawable.ic_apps, Modifier.rect(rect),
					enabled = ready && "launch" in status?.actions.orEmpty(), onClick = actions.onApps)
				RemoteModule.More -> KeyCap("More controls", R.drawable.ic_more_horiz, Modifier.rect(rect), onClick = actions.onMore)
				else -> Unit
			}
		}
	}
}

/** The line around an element while editing; [onRemove] adds the badge that hides it. */
@Composable
private fun Outline(rect: Rect, module: RemoteModule?, onRemove: (() -> Unit)? = null) {
	Box(Modifier.rect(rect).border(1.5.dp, lucent.accentText, RoundedCornerShape(Radius.control)))
	if (onRemove != null && module != null) {
		// A 48 dp touch area around a 24 dp badge that sits inside the element's corner.
		Box(
			Modifier.offset((rect.right - 38f).dp, (rect.top - 10f).dp).size(48.dp)
				.clickable(role = Role.Button, onClickLabel = "Hide ${module.label}", onClick = onRemove)
				.semantics { contentDescription = "Hide ${module.label}" },
			contentAlignment = Alignment.Center,
		) {
			Box(Modifier.size(24.dp).clip(CircleShape).background(lucent.accent), contentAlignment = Alignment.Center) {
				Icon(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp), tint = lucent.accentInk)
			}
		}
	}
}

/**
 * Along the panel's foot while editing: the elements that are off, to put back, and any that the panel is too small to
 * hold (dimmed; a tap drops them from the choice).
 */
@Composable
internal fun ModuleTray(hidden: List<RemoteModule>, dropped: List<RemoteModule>, rect: Rect, onAdd: (RemoteModule) -> Unit, onRemove: (RemoteModule) -> Unit) {
	Row(
		Modifier.rect(rect).horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically,
	) {
		for (module in hidden) ModuleChip(module.label, false) { onAdd(module) }
		for (module in dropped) ModuleChip(module.label, true) { onRemove(module) }
	}
}

@Composable
private fun ModuleChip(label: String, dimmed: Boolean, onClick: () -> Unit) {
	val shape = RoundedCornerShape(percent = 50)
	Row(
		Modifier.height(48.dp).graphicsLayer { alpha = if (dimmed) 0.5f else 1f }.clip(shape).background(lucent.glassRaised)
			.border(1.dp, lucent.edge, shape)
			.clickable(role = Role.Button, onClickLabel = if (dimmed) "Drop $label" else "Show $label", onClick = onClick)
			.padding(horizontal = Space.s3),
		horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically,
	) {
		if (!dimmed) Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp), tint = lucent.accentText)
		Text(label, color = lucent.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
	}
}

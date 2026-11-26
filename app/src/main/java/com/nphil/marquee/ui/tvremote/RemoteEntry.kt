package com.nphil.marquee.ui.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.TvRemote
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** What the UI tests find the remote's two entry points by. */
internal object RemoteEntryTags {
	const val Button = "tv-remote-button"
	const val MiniBar = "tv-remote-minibar"
}

/**
 * The remote's button beside the profile button: the avatar's 32 dp circle in the same colours, in a 48 dp touch area.
 * It is an ordinary toolbar item with no dwell, dimming or movement of its own. The panel grows out of it.
 */
@Composable
internal fun RemoteEntryButton(remote: TvRemote, modifier: Modifier = Modifier) {
	var bounds by remember { mutableStateOf(Rect.Zero) }
	Box(
		modifier
			.size(Size.target)
			.testTag(RemoteEntryTags.Button)
			.onGloballyPositioned { bounds = it.boundsInWindow() }
			.clip(CircleShape)
			.clickable(role = Role.Button, onClickLabel = "Open remote") { remote.open(bounds) }
			.semantics { contentDescription = "TV Remote" },
		contentAlignment = Alignment.Center,
	) {
		Box(
			Modifier.size(Space.s7).clip(CircleShape).background(lucent.trackOff, CircleShape).border(1.dp, lucent.edgeRaised, CircleShape),
			contentAlignment = Alignment.Center,
		) {
			Icon(painterResource(R.drawable.ic_tv_remote), null, Modifier.size(18.dp), tint = lucent.accentText)
		}
	}
}

/**
 * The Mini Bar: a 48 dp capsule at the foot of the pages that opens the panel. While a playback is minimised its bar
 * sits just under this one, so both stay in reach.
 */
@Composable
internal fun RemoteMiniBar(remote: TvRemote, state: TvRemote.State, modifier: Modifier = Modifier) {
	var bounds by remember { mutableStateOf(Rect.Zero) }
	val shape = RoundedCornerShape(percent = 50)
	val name = state.current?.name ?: "LumaTV"
	val status = statusText(state.current, state.status)
	Row(
		modifier.padding(horizontal = Space.s5).padding(bottom = Space.s2).fillMaxWidth().height(Size.target)
			.testTag(RemoteEntryTags.MiniBar)
			.onGloballyPositioned { bounds = it.boundsInWindow() }
			.clip(shape).background(lucent.glassRaised, shape).border(1.dp, lucent.edge, shape)
			.clickable(role = Role.Button, onClickLabel = "Open remote") { remote.open(bounds) }
			.semantics(mergeDescendants = true) { contentDescription = "TV Remote, $name, $status" }
			.padding(horizontal = Space.s4),
		horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(painterResource(R.drawable.ic_tv), null, Modifier.size(20.dp), tint = lucent.accentText)
		Text(name, Modifier.weight(1f, fill = false), color = lucent.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1,
			overflow = TextOverflow.Ellipsis)
		Text(status, Modifier.weight(1f), color = lucent.ink2, style = MaterialTheme.typography.bodySmall, maxLines = 1,
			overflow = TextOverflow.Ellipsis)
		Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(20.dp).rotate(180f), tint = lucent.ink2)
	}
}

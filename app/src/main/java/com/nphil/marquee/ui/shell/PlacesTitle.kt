package com.nphil.marquee.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.nphil.marquee.R
import com.nphil.marquee.core.Destination
import com.nphil.marquee.ui.browse.ActionMenu
import com.nphil.marquee.ui.browse.MenuAction
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** A page's title in the app bar; with [places] it opens a menu of them (the iPhone's Home title menu). */
@Composable
internal fun PlacesTitle(title: String, places: List<Destination>, open: (Destination) -> Unit) {
	if (places.isEmpty()) {
		Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
		return
	}
	var expanded by remember { mutableStateOf(false) }
	val actions = remember(places, open) { places.map { MenuAction(it.icon, it.title) { open(it) } } }
	Box {
		Row(
			modifier = Modifier
				.clip(RoundedCornerShape(Radius.control))
				.clickable(role = Role.Button, onClickLabel = "Open a place") { expanded = true }
				.heightIn(min = Size.target)
				.padding(horizontal = Space.s2)
				.semantics { heading() },
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Space.s1),
		) {
			Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = lucent.ink2)
		}
		ActionMenu(expanded, { expanded = false }, actions)
	}
}

package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.nphil.marquee.R
import com.nphil.marquee.tvremote.RemoteAccess
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.lucent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.container
import com.nphil.marquee.ui.theme.Space

@Composable
internal fun TvRemoteSettings(padding: PaddingValues) {
	val remote = LocalContext.current.container.tvRemote
	val state by remote.state.collectAsStateWithLifecycle()
	BrowseList(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
		item { SettingsSwitchRow(0, 1, "LumaTV Remote", state.enabled, enabled = true, onChange = remote::enable) }
		if (state.enabled) {
			item { Heading("Remote Access") }
			itemsIndexed(RemoteAccess.entries) { index, access ->
				GroupRow(
					index = index,
					count = RemoteAccess.entries.size,
					modifier = Modifier.semantics {
						role = Role.RadioButton
						selected = state.access == access
					},
					onClick = { remote.setAccess(access) },
				) {
					Text(access.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
					if (state.access == access) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.accentText)
				}
			}
		}
		item {
			SettingsFootnote(if (state.enabled) "Opens from a button beside the profile button (in the sidebar on a tablet, on Home on a phone), or from the Mini Bar above the pages."
				else "Control a LumaTV on the same network.")
		}
	}
}

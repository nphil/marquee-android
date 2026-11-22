package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
		item {
			SettingsFootnote(if (state.enabled) "Open TV Remote above Settings in the sidebar, or next to Settings on Home."
				else "Control a LumaTV on the same network.")
		}
	}
}

package com.nphil.marquee.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.DvrAiring
import com.nphil.marquee.core.DvrItem
import com.nphil.marquee.core.DvrMark
import com.nphil.marquee.core.DvrRule
import com.nphil.marquee.core.ItemText
import com.nphil.marquee.live.DvrModel
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.Recordings
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.PlexArt
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.player.LocalDockRoom
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsDestination(container: AppContainer, route: Recordings, back: () -> Unit) {
	val guide by container.live.guide.collectAsStateWithLifecycle()
	val model = guide?.takeIf { it.key == route.scope }?.dvr
	val navigator = LocalNavigator.current
	// The player's bar, while it shows, sits above the navigation bar and takes the room under the page.
	val room = LocalDockRoom.current
	BrowseScaffold(topBar = {
		TopAppBar(title = { Text("Recordings", Modifier.semantics { heading() }) }, navigationIcon = { BackArrow(back) })
	}) { padding ->
		if (model == null) {
			EmptyState(R.drawable.ic_tv, "Recordings unavailable", "Open Live TV on the active profile.", Modifier.fillMaxSize().padding(padding).padding(bottom = room))
		} else {
			RecordingBrowserScreen(model, Modifier.fillMaxSize().padding(padding).padding(bottom = room)) { item ->
				navigator?.openRecording(item.ratingKey, item.titleKey, item.mediaID)
			}
		}
	}
}

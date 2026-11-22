package com.nphil.marquee.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.browse.BrowseEnvironment
import com.nphil.marquee.browse.BrowsePlace
import com.nphil.marquee.browse.HubGridModel
import com.nphil.marquee.ui.LocalNavigator
import com.nphil.marquee.ui.components.BackArrow
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.theme.lucent

/** A hub's whole list ("See all"), over the page it came from; Back returns to that page. Home and Library share it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubGridScreen(
	env: BrowseEnvironment,
	place: BrowsePlace,
	key: String,
	title: String,
	onBack: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val model: HubGridModel = viewModel(key = "hub:${place.key}:$key") { HubGridModel(env, key) }
	val paged by model.state.collectAsStateWithLifecycle()
	val navigator = LocalNavigator.current
	BackHandler(onBack = onBack)
	BrowseScaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text(title) },
				navigationIcon = { BackArrow(onBack) },
				colors = TopAppBarDefaults.topAppBarColors(containerColor = lucent.canvas, scrolledContainerColor = lucent.canvas),
			)
		},
	) { padding ->
		PullToRefreshBox(
			isRefreshing = paged.isLoading && paged.items.isNotEmpty(),
			onRefresh = model::refresh,
			modifier = Modifier.padding(padding).fillMaxSize(),
		) {
			PagedPosterGrid(
				paged = paged,
				serverName = place.server.name,
				onRetry = model::load,
				onSignIn = onSignIn,
				onNearEnd = model::nearEnd,
				modifier = Modifier.fillMaxSize(),
				empty = { EmptyState(R.drawable.ic_video_library, "Nothing here", "The server lists no titles here.") },
			) { item, width ->
				ItemPoster(item, width, onClick = { navigator?.openItem(item.ratingKey) })
			}
		}
	}
}

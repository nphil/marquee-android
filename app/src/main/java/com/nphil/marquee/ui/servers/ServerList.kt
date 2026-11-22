package com.nphil.marquee.ui.servers

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.core.PlexConnection
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.core.Reach
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.BrowseList
import com.nphil.marquee.ui.components.BrowseScaffold
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.components.TextAction
import com.nphil.marquee.ui.settings.SignOutDialog
import com.nphil.marquee.ui.theme.LucentTheme
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

/** Rows wider than this read badly on a tablet in landscape. */
val ContentWidth = 720.dp

/** The server list as the whole screen, after sign-in: choosing one opens the app on it. */
@Composable
fun ServerChoiceScreen(session: AppSession) {
	var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
	BrowseScaffold(
		topBar = {
			TopAppBar(
				title = { Text("Servers") },
				actions = { TextAction("Sign Out", onClick = { confirmingSignOut = true }) },
			)
		},
	) { padding ->
		ServerList(session, isRoot = true, onChosen = {}, contentPadding = padding)
	}
	if (confirmingSignOut) SignOutDialog(onConfirm = session::signOut, onDismiss = { confirmingSignOut = false })
}

/**
 * The account's servers (DESIGN §8.0): one row each with the name, the owner and how the tablet reaches it.
 * Away from [isRoot], the server in use is ticked and shows the address it is reached at.
 */
@Composable
fun ServerList(
	session: AppSession,
	isRoot: Boolean,
	onChosen: () -> Unit,
	contentPadding: PaddingValues,
	modifier: Modifier = Modifier,
) {
	val state by session.state.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()
	var refreshing by remember { mutableStateOf(false) }
	// Each pull starts a new probe round; the first round reuses what Core already knows.
	var round by rememberSaveable { mutableIntStateOf(0) }
	val refresh: () -> Unit = {
		scope.launch {
			refreshing = true
			session.reloadServers()
			round++
			refreshing = false
		}
	}
	val servers = state.servers
	val currentId = if (isRoot) null else state.server?.id
	if (servers.isEmpty()) {
		Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
			EmptyState(
				icon = R.drawable.ic_dns,
				title = "No servers found",
				message = "Servers on this Plex account, its Home and those shared with it show here.",
				action = { RetryButton("Retry", onClick = refresh) },
			)
		}
		return
	}
	val direction = LocalLayoutDirection.current
	PullToRefreshBox(
		isRefreshing = refreshing,
		onRefresh = refresh,
		modifier = modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
	) {
		BrowseList(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = contentPadding.calculateStartPadding(direction) + Space.inset,
				end = contentPadding.calculateEndPadding(direction) + Space.inset,
				top = Space.s2,
				bottom = contentPadding.calculateBottomPadding() + Space.inset,
			),
			verticalArrangement = Arrangement.spacedBy(GroupGap),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			itemsIndexed(servers, key = { _, server -> server.id }) { index, server ->
				ServerRow(
					server = server,
					index = index,
					count = servers.size,
					isCurrent = server.id == currentId,
					round = round,
					session = session,
					onClick = {
						session.choose(server)
						onChosen()
					},
				)
			}
		}
	}
}

@Composable
private fun ServerRow(
	server: PlexServer,
	index: Int,
	count: Int,
	isCurrent: Boolean,
	round: Int,
	session: AppSession,
	onClick: () -> Unit,
) {
	val probe by produceState<Probe>(Probe.Checking, server, round) {
		value = Probe.Checking
		val reach = session.probe(server, refresh = round > 0)
		value = if (reach?.connection != null) Probe.Reached(reach) else Probe.Offline
	}
	GroupRow(
		index = index,
		count = count,
		modifier = Modifier.widthIn(max = ContentWidth).semantics { selected = isCurrent },
		onClick = onClick,
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(server.name, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			Text(owner(server), style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
			val reach = (probe as? Probe.Reached)?.reach
			if (isCurrent && reach != null) {
				reach.inUse?.let { Text("In use: $it", style = MaterialTheme.typography.labelLarge, color = lucent.ink2) }
				reach.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = lucent.ink3) }
			}
		}
		ReachLabel(probe.kind)
		if (isCurrent) {
			Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
		}
	}
}

private fun owner(server: PlexServer) = when {
	server.isOwned -> "Yours"
	server.isHome -> server.ownerName?.let { "Plex Home · $it" } ?: "Plex Home"
	else -> server.ownerName?.let { "Shared by $it" } ?: "Shared with you"
}

private sealed interface Probe {
	val kind: ReachKind

	data object Checking : Probe {
		override val kind = ReachKind.Checking
	}

	data object Offline : Probe {
		override val kind = ReachKind.Offline
	}

	data class Reached(val reach: Reach) : Probe {
		override val kind = reach.connection?.let(ReachKind::of) ?: ReachKind.Offline
	}
}

/** How the tablet reaches a server, as a glyph in the tone of the answer and a word, so colour is never alone. */
private enum class ReachKind(val word: String, @DrawableRes val icon: Int) {
	Checking("Checking", R.drawable.ic_pending),
	Local("Local", R.drawable.ic_wifi),
	Remote("Remote", R.drawable.ic_public),
	Relay("Relay", R.drawable.ic_alt_route),
	Offline("Offline", R.drawable.ic_wifi_off),
	;

	/** DESIGN §5.2: positive is fine, info ordinary, warning slow, danger down. */
	fun tone(theme: LucentTheme): Color = when (this) {
		Checking -> theme.ink2
		Local -> theme.positive
		Remote -> theme.info
		Relay -> theme.warning
		Offline -> theme.danger
	}

	companion object {
		fun of(connection: PlexConnection) = when {
			connection.isRelay -> Relay
			connection.isLocal -> Local
			else -> Remote
		}
	}
}

@Composable
private fun ReachLabel(kind: ReachKind) {
	Crossfade(targetState = kind, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "reach") { shown ->
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Icon(painterResource(shown.icon), contentDescription = null, tint = shown.tone(lucent), modifier = Modifier.size(20.dp))
			Text(shown.word, style = MaterialTheme.typography.labelLarge, color = lucent.ink2)
		}
	}
}

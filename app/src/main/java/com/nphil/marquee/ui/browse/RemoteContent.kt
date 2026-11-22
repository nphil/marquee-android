package com.nphil.marquee.ui.browse

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.browse.Remote
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.Paged
import com.nphil.marquee.ui.components.EmptyState
import com.nphil.marquee.ui.components.RetryButton
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay

// The states around a screen's content (DESIGN §11): a static skeleton while the first answer is on its way, a failure
// page when there is nothing to show, and once content exists, the content with a banner above it when a later
// refresh failed.

/** What a failed load means to the person: another sign-in, a server that does not answer, or an answer that was no good. */
enum class FailureKind { Unauthorized, Unreachable, Other }

fun Throwable.failureKind(): FailureKind = when ((this as? CoreException)?.kind) {
	CoreException.Kind.Unauthorized -> FailureKind.Unauthorized
	// An error that is not the server's own answer is the network's.
	null, CoreException.Kind.Unreachable, CoreException.Kind.NoConnections -> FailureKind.Unreachable
	else -> FailureKind.Other
}

/** [remote]'s content, with the offline banner over it when a later refresh failed, else why there is none, else [skeleton]. */
@Composable
fun <T : Any> RemoteContent(
	remote: Remote<T>,
	serverName: String,
	onRetry: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
	skeleton: @Composable () -> Unit,
	content: @Composable (T) -> Unit,
) {
	val value = remote.value
	val failure = remote.failure
	val loadedAt = remote.loadedAt
	Box(modifier) {
		when {
			value != null -> Column(Modifier.fillMaxSize()) {
				if (failure != null && loadedAt != null && failure.failureKind() != FailureKind.Unauthorized) {
					OfflineBanner(serverName, loadedAt, isRetrying = remote.isLoading, onRetry = onRetry)
				}
				Box(Modifier.weight(1f)) { content(value) }
			}
			failure != null && !remote.isLoading ->
				FailureState(failure, serverName, onRetry, onSignIn, Modifier.align(Alignment.Center))
			else -> {
				skeleton()
				LoadingNote(onRetry, Modifier.align(Alignment.Center))
			}
		}
	}
}

/**
 * [RemoteContent] for a list that loads a page at a time. A list the server answered with no titles at all shows
 * [empty]. A screen that places the offline banner itself turns [banner] off.
 */
@Composable
fun <Item : Any> PagedContent(
	paged: Paged<Item>,
	serverName: String,
	onRetry: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
	banner: Boolean = true,
	skeleton: @Composable () -> Unit,
	empty: @Composable () -> Unit,
	content: @Composable () -> Unit,
) {
	val failure = paged.failure
	val loadedAt = paged.loadedAt
	Box(modifier) {
		when {
			paged.items.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
				if (banner && failure != null && loadedAt != null && failure.failureKind() != FailureKind.Unauthorized) {
					OfflineBanner(serverName, loadedAt, isRetrying = paged.isLoading, onRetry = onRetry)
				}
				Box(Modifier.weight(1f)) { content() }
			}
			failure != null && !paged.isLoading ->
				FailureState(failure, serverName, onRetry, onSignIn, Modifier.align(Alignment.Center))
			loadedAt != null && !paged.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { empty() }
			else -> {
				skeleton()
				LoadingNote(onRetry, Modifier.align(Alignment.Center))
			}
		}
	}
}

/** Nothing to show and the last load failed. */
@Composable
fun FailureState(
	failure: Throwable,
	serverName: String,
	onRetry: () -> Unit,
	onSignIn: () -> Unit,
	modifier: Modifier = Modifier,
) {
	when (failure.failureKind()) {
		FailureKind.Unauthorized -> EmptyState(
			R.drawable.ic_account_circle, "Sign in again", "Plex no longer accepts this sign-in.", modifier,
		) { ActionButton(R.drawable.ic_account_circle, "Sign In", onSignIn) }
		FailureKind.Unreachable -> EmptyState(
			R.drawable.ic_wifi_off, "Can't reach $serverName", "Check that it is on and this device is online.", modifier,
		) { RetryButton("Retry", onRetry) }
		FailureKind.Other -> EmptyState(
			R.drawable.ic_error, "Something went wrong", "$serverName sent an answer Marquee did not expect.", modifier,
		) { RetryButton("Retry", onRetry) }
	}
}

/** The action under an [EmptyState]: a tonal pill with a glyph. */
@Composable
fun ActionButton(@DrawableRes icon: Int, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = Size.target)) {
		Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
		Spacer(Modifier.width(Space.s2))
		Text(text, style = MaterialTheme.typography.labelLarge)
	}
}

/**
 * Words over a skeleton: none for the first second, then "Loading", then after a real wait "Still loading" with Retry.
 */
@Composable
fun LoadingNote(onRetry: () -> Unit, modifier: Modifier = Modifier) {
	var stage by remember { mutableStateOf(LoadingStage.Quiet) }
	LaunchedEffect(Unit) {
		delay(1_000)
		stage = LoadingStage.Loading
		delay(14_000)
		stage = LoadingStage.Slow
	}
	if (stage == LoadingStage.Quiet) return
	Column(
		modifier = modifier
			.clip(RoundedCornerShape(Radius.card))
			.background(lucent.canvas)
			.padding(Space.s5)
			.semantics { liveRegion = LiveRegionMode.Polite },
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Text(
			if (stage == LoadingStage.Slow) "Still loading" else "Loading",
			style = MaterialTheme.typography.labelLarge,
			color = lucent.ink2,
		)
		if (stage == LoadingStage.Slow) RetryButton("Retry", onRetry)
	}
}

private enum class LoadingStage { Quiet, Loading, Slow }

/**
 * The row over content that could not be refreshed: a card with the age of what is shown, and Retry.
 * [since] is when the content arrived, in epoch milliseconds.
 */
@Composable
fun OfflineBanner(
	serverName: String,
	since: Long,
	isRetrying: Boolean,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
) {
	// The age changes by the minute, so only this row recomposes as it does.
	val now by produceState(System.currentTimeMillis(), since) {
		while (true) {
			delay(60_000)
			value = System.currentTimeMillis()
		}
	}
	val shape = RoundedCornerShape(Radius.card)
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = Space.inset, vertical = Space.s2)
			.clip(shape)
			.background(lucent.glass)
			.border(1.dp, lucent.edge, shape)
			.padding(start = Space.s4, end = Space.s2, top = Space.s1, bottom = Space.s1),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		Icon(painterResource(R.drawable.ic_wifi_off), contentDescription = null, tint = lucent.ink2)
		Text(
			"Can't reach $serverName. Showing ${offlineAge(since, now)}.",
			style = MaterialTheme.typography.bodyMedium,
			color = lucent.ink,
			modifier = Modifier.weight(1f),
		)
		Box(
			modifier = Modifier
				.heightIn(min = Size.target)
				.widthIn(min = Size.target)
				.alpha(if (isRetrying) 0.4f else 1f)
				.clickable(enabled = !isRetrying, role = Role.Button, onClick = onRetry)
				.padding(horizontal = Space.s2),
			contentAlignment = Alignment.Center,
		) {
			Text("Retry", style = MaterialTheme.typography.titleSmall, color = lucent.accentText)
		}
	}
}

/** "what loaded just now", "12 min ago", "3 h ago". */
internal fun offlineAge(since: Long, now: Long): String {
	val minutes = ((now - since) / 60_000).toInt()
	return when {
		minutes < 1 -> "what loaded just now"
		minutes < 60 -> "$minutes min ago"
		else -> "${minutes / 60} h ago"
	}
}

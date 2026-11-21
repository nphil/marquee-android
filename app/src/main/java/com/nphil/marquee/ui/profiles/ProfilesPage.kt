package com.nphil.marquee.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.Avatar
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.servers.ContentWidth
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

/**
 * The Plex Home's profiles (DESIGN §7). A protected profile asks for its PIN in a sheet; an account outside
 * a Home lists only itself.
 */
@Composable
fun ProfilesPage(session: AppSession, contentPadding: PaddingValues) {
	val state by session.state.collectAsStateWithLifecycle()
	val profiles by remember {
		derivedStateOf { state.homeUsers.ifEmpty { listOfNotNull(state.user) } }
	}
	val scope = rememberCoroutineScope()
	val haptics = LocalHapticFeedback.current
	var switching by remember { mutableStateOf<PlexHomeUser?>(null) }
	var failed by remember { mutableStateOf<PlexHomeUser?>(null) }
	var askingPinFor by rememberSaveable { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) { session.reloadHomeUsers() }

	fun pick(profile: PlexHomeUser) {
		if (profile.uuid == state.user?.uuid) return
		failed = null
		if (profile.isProtected) {
			askingPinFor = profile.uuid
			return
		}
		switching = profile
		scope.launch {
			try {
				session.switchUser(profile, pin = null)
				haptics.performHapticFeedback(HapticFeedbackType.Confirm)
			} catch (_: CoreException) {
				haptics.performHapticFeedback(HapticFeedbackType.Reject)
				failed = profile
			}
			switching = null
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		itemsIndexed(profiles, key = { _, profile -> profile.uuid }) { index, profile ->
			ProfileRow(
				profile = profile,
				index = index,
				count = profiles.size,
				isCurrent = profile.uuid == state.user?.uuid,
				isSwitching = profile == switching,
				onClick = if (switching == null) ({ pick(profile) }) else null,
			)
		}
		failed?.let { profile ->
			item(key = "failed") {
				ErrorLine(
					"Couldn't switch to ${profile.title}. Check the connection and try again.",
					Modifier.widthIn(max = ContentWidth).padding(top = Space.s4),
				)
			}
		}
	}

	val pinProfile = profiles.firstOrNull { it.uuid == askingPinFor }
	if (pinProfile != null) {
		PinSheet(profile = pinProfile, session = session, onDismiss = { askingPinFor = null })
	}
}

@Composable
private fun ProfileRow(
	profile: PlexHomeUser,
	index: Int,
	count: Int,
	isCurrent: Boolean,
	isSwitching: Boolean,
	onClick: (() -> Unit)?,
) {
	GroupRow(
		index = index,
		count = count,
		modifier = Modifier.widthIn(max = ContentWidth).semantics { selected = isCurrent },
		onClick = onClick,
	) {
		Avatar(profile, size = Size.target - Space.s2)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(profile.title, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			if (profile.isRestricted) {
				Text("Restricted", style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
			}
		}
		when {
			isSwitching -> LoadingIndicator(modifier = Modifier.size(24.dp))
			isCurrent -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
			profile.isProtected -> Icon(painterResource(R.drawable.ic_lock), contentDescription = "Needs a PIN", tint = lucent.ink2)
		}
	}
}

package com.nphil.marquee.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.container
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.seerr.SeerrText
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

private enum class Field { Address, Key }

/**
 * Settings › Integrations › Seerr: the switch that makes Requests exist, and under it, once on, where Seerr is and how
 * to sign in. The switch is always there to turn; what is typed is tested by itself, and Requests appears when the
 * test works.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SeerrPage(contentPadding: PaddingValues) {
	val container = LocalContext.current.container
	val model = viewModel { SeerrPageModel(container.seerr) }
	val config by model.config.collectAsStateWithLifecycle()
	val draft by model.draft.collectAsStateWithLifecycle()
	val parse by model.parse.collectAsStateWithLifecycle()
	val check by model.check.collectAsStateWithLifecycle()
	var focused by remember { mutableStateOf<Field?>(null) }
	var forgetting by rememberSaveable { mutableStateOf(false) }
	var focusAddress by remember { mutableStateOf(false) }
	val addressFocus = remember { FocusRequester() }
	val keyboard = LocalSoftwareKeyboardController.current

	// Turning Seerr on with nothing set up asks for the address first. The field is composed by the reveal, so it
	// is focused once a frame has laid it out.
	LaunchedEffect(focusAddress, config.on) {
		if (focusAddress && config.on) {
			withFrameNanos { }
			runCatching { addressFocus.requestFocus() }.onSuccess { keyboard?.show() }
			focusAddress = false
		}
	}

	// The saved settings are read before anything is drawn, so a page opened with Seerr on is not revealed by motion.
	if (!config.loaded) return

	val problem = parse?.problem
	val problemField = if (SeerrText.isKeyProblem(problem)) Field.Key else Field.Address
	val shown = SeerrText.shownProblem(problem, if (problemField == Field.Key) draft.key else draft.address, focused == problemField)
	val testing = check == SeerrText.Check.Testing
	val rows = if (draft.plex) 2 else 3
	fun track(field: Field): (Boolean) -> Unit = { focus ->
		if (focus) focused = field else if (focused == field) focused = null
	}

	BrowseList(
		modifier = Modifier.fillMaxSize().imePadding(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "enabled") {
			SettingsSwitchRow(0, 1, "Enabled", config.on, enabled = true, onChange = { on ->
				if (on && draft.address.isBlank()) focusAddress = true
				model.setOn(on)
			})
		}
		item(key = "about") {
			SettingsFootnote(SeerrText.ABOUT, Modifier.padding(top = Space.s2, bottom = Space.s5))
		}
		item(key = "setup") {
			AnimatedVisibility(
				visible = config.on,
				enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
				exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
					SettingsFieldRow(
						0, rows, "Address", draft.address, "https://seerr.local",
						onChange = model::editAddress,
						onFocus = track(Field.Address),
						keyboardType = KeyboardType.Uri,
						imeAction = ImeAction.Done,
						onDone = model::test,
						focusRequester = addressFocus,
					)
					SignInRow(1, rows, draft.plex, model::setPlex)
					if (!draft.plex) {
						SettingsFieldRow(
							2, rows, "API Key", draft.key, "Required",
							onChange = model::editKey,
							onFocus = track(Field.Key),
							imeAction = ImeAction.Done,
							secret = true,
							onDone = model::test,
						)
					}
					if (shown != null) {
						ErrorLine(shown, Modifier.padding(horizontal = Space.s4, vertical = Space.s2))
					} else {
						SettingsFootnote(SeerrText.INTRO, Modifier.padding(top = Space.s2))
					}
					SettingsFootnote(if (draft.plex) SeerrText.PLEX_NOTE else SeerrText.KEY_NOTE, Modifier.padding(top = Space.s2, bottom = Space.s5))

					SettingsActionRow(
						0, 1, R.drawable.ic_wifi, "Test Connection",
						enabled = draft.complete && problem == null && !testing,
						busy = testing,
						onClick = model::test,
					)
					val line = SeerrText.status(draft.complete && problem == null, check)
					Row(
						modifier = Modifier
							.padding(horizontal = Space.s4)
							.padding(top = Space.s2, bottom = Space.s5)
							.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Space.s2),
					) {
						when (line.tone) {
							SeerrText.Tone.Problem -> ErrorLine(line.text)
							SeerrText.Tone.Good -> {
								Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.positive, modifier = Modifier.size(20.dp))
								Text(line.text, style = MaterialTheme.typography.labelLarge, color = lucent.ink)
							}
							SeerrText.Tone.Neutral -> Text(line.text, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
						}
					}

					SettingsSwitchRow(0, 1, "Pulsarr", config.pulsarr, enabled = true, onChange = model::setPulsarr)
					SettingsFootnote(SeerrText.PULSARR_NOTE, Modifier.padding(top = Space.s2, bottom = Space.s5))

					if (config.configured) {
						SettingsActionRow(0, 1, R.drawable.ic_cloud_off, "Forget Seerr", enabled = true, tint = lucent.danger, onClick = { forgetting = true })
					}
				}
			}
		}
	}

	if (forgetting) ForgetDialog(onConfirm = { forgetting = false; model.forget() }, onDismiss = { forgetting = false })
}

/** Plex Profile and API Key as a connected button group, the way Diagnostic Logs picks its detail. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SignInRow(index: Int, count: Int, plex: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(index, count) {
		Text("Sign In With", style = MaterialTheme.typography.titleMedium, color = lucent.ink, modifier = Modifier.weight(1f))
		Row(Modifier.width(SIGN_IN_WIDTH), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
			listOf(true to "Plex Profile", false to "API Key").forEachIndexed { position, (value, label) ->
				ToggleButton(
					checked = plex == value,
					onCheckedChange = { onChange(value) },
					shapes = if (position == 0) {
						ButtonGroupDefaults.connectedLeadingButtonShapes()
					} else {
						ButtonGroupDefaults.connectedTrailingButtonShapes()
					},
					modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
				) { Text(label, maxLines = 1) }
			}
		}
	}
}

@Composable
private fun ForgetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
	DisposableEffect(Unit) {
		DiagScreen.presentation("forgetSeerr", true)
		onDispose { DiagScreen.presentation("forgetSeerr", false) }
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(SeerrText.FORGET_TITLE) },
		text = { Text(SeerrText.FORGET_MESSAGE) },
		confirmButton = {
			TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = lucent.danger)) { Text("Forget") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Cancel") }
		},
		titleContentColor = lucent.ink,
		textContentColor = lucent.ink2,
	)
}

private val SIGN_IN_WIDTH = 260.dp

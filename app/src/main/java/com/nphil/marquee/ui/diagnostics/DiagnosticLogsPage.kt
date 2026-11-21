package com.nphil.marquee.ui.diagnostics

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.R
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCheck
import com.nphil.marquee.diagnostics.DiagField
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.diagnostics.DiagText
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Diagnostic Logs (DESIGN §8.12): the ntfy server, topic and access token the log goes to, this tablet's name on
 * its messages, live sending, how much is kept, the floating report button, a connection test and a report on
 * demand. A saved server and topic are what turn it on.
 */
@Composable
fun DiagnosticLogsPage(contentPadding: PaddingValues) {
	val model = viewModel<DiagnosticsModel>()
	val config by model.config.collectAsStateWithLifecycle()
	val draft by model.draft.collectAsStateWithLifecycle()
	val parse by model.parse.collectAsStateWithLifecycle()
	val name by model.name.collectAsStateWithLifecycle()
	val check by model.check.collectAsStateWithLifecycle()
	val status by model.status.collectAsStateWithLifecycle()
	val defaultName by Diag.defaultName.collectAsStateWithLifecycle()
	val title by Diag.title.collectAsStateWithLifecycle()
	var left by remember { mutableStateOf(emptySet<DiagField>()) }
	var focused by remember { mutableStateOf<DiagField?>(null) }
	var forgetting by rememberSaveable { mutableStateOf(false) }
	val activity = LocalContext.current.findActivity()
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val clock = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

	LaunchedEffect(model, lifecycle) {
		lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (true) {
				model.refresh()
				delay(STATUS_MILLIS)
			}
		}
	}

	val configured = config?.configured == true
	val current = config
	val shown = DiagText.shownProblem(parse?.problem, draft, left, focused)
	val testing = check == DiagCheck.Testing
	fun track(field: DiagField): (Boolean) -> Unit = { focus ->
		if (focus) {
			focused = field
		} else {
			if (focused == field) focused = null
			left = left + field
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize().imePadding(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(GroupGap),
	) {
		item(key = "server") {
			FieldRow(
				0, 3, "Server", draft.server, "https://ntfy.nateshome.net",
				onChange = { model.edit(DiagField.Server, it) },
				onFocus = track(DiagField.Server),
				keyboardType = KeyboardType.Uri,
			)
		}
		item(key = "topic") {
			FieldRow(
				1, 3, "Topic", draft.topic, "Required",
				onChange = { model.edit(DiagField.Topic, it) },
				onFocus = track(DiagField.Topic),
			)
		}
		item(key = "token") {
			FieldRow(
				2, 3, "Access Token", draft.token, "Optional",
				onChange = { model.edit(DiagField.Token, it) },
				onFocus = track(DiagField.Token),
				imeAction = ImeAction.Done,
				secret = true,
				onDone = model::test,
			)
		}
		item(key = "intro") {
			if (shown != null) {
				ErrorLine(DiagText.problem(shown), Modifier.padding(horizontal = Space.s4, vertical = Space.s2))
			} else {
				Footnote(DiagText.INTRO, Modifier.padding(top = Space.s2, bottom = Space.s5))
			}
		}

		item(key = "name") {
			FieldRow(
				0, 4, "Name", name, defaultName,
				onChange = model::editName,
				onFocus = {},
				imeAction = ImeAction.Done,
				enabled = configured,
				capitalize = true,
			)
		}
		item(key = "live") {
			SwitchRow(1, 4, "Send Live Logs", current?.live == true, configured, model::setLive)
		}
		item(key = "detail") {
			DetailRow(2, 4, current?.detailed == true, configured, model::setDetailed)
		}
		item(key = "button") {
			SwitchRow(3, 4, "Floating Report Button", current?.button == true, configured, model::setButton)
		}
		item(key = "options") {
			Footnote(DiagText.options(title), Modifier.padding(top = Space.s2, bottom = Space.s5))
		}

		item(key = "test") {
			ActionRow(0, 2, R.drawable.ic_wifi, "Test Connection", enabled = parse?.problem == null && draft.server.isNotBlank() && draft.topic.isNotBlank() && !testing, busy = testing, onClick = model::test)
		}
		item(key = "report") {
			ActionRow(1, 2, R.drawable.ic_bug_report, "Send Report Now", enabled = configured, onClick = { activity?.let { ReportDesk.open(it, "settings") } })
		}
		item(key = "status") {
			Column(
				modifier = Modifier
					.padding(horizontal = Space.s4)
					.padding(top = Space.s2, bottom = Space.s5)
					.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
				verticalArrangement = Arrangement.spacedBy(Space.s2),
			) {
				LineText(DiagText.status(configured, check))
				DiagText.live(status) { clock.format(Date(it)) }?.let { LineText(it) }
			}
		}

		if (configured) {
			item(key = "forget") {
				ActionRow(0, 1, R.drawable.ic_cloud_off, "Forget This Server", enabled = true, tint = lucent.danger, onClick = { forgetting = true })
			}
		}
	}

	if (forgetting) ForgetDialog(onConfirm = { forgetting = false; model.forget() }, onDismiss = { forgetting = false })
}

@Composable
private fun FieldRow(
	index: Int,
	count: Int,
	label: String,
	value: String,
	hint: String,
	onChange: (String) -> Unit,
	onFocus: (Boolean) -> Unit,
	keyboardType: KeyboardType = KeyboardType.Text,
	imeAction: ImeAction = ImeAction.Next,
	secret: Boolean = false,
	enabled: Boolean = true,
	capitalize: Boolean = false,
	onDone: () -> Unit = {},
) {
	var focusedOnce by remember { mutableStateOf(false) }
	val focusManager = LocalFocusManager.current
	GroupRow(index, count) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) lucent.ink else lucent.ink3)
		BasicTextField(
			value = value,
			onValueChange = onChange,
			modifier = Modifier
				.weight(1f)
				.onFocusChanged { state ->
					if (state.isFocused) {
						focusedOnce = true
						onFocus(true)
					} else if (focusedOnce) {
						onFocus(false)
					}
				}
				.semantics { contentDescription = label },
			enabled = enabled,
			singleLine = true,
			textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) lucent.ink else lucent.ink3, textAlign = TextAlign.End),
			cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
			visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
			keyboardOptions = KeyboardOptions(
				capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
				autoCorrectEnabled = false,
				keyboardType = if (secret) KeyboardType.Password else keyboardType,
				imeAction = imeAction,
			),
			keyboardActions = KeyboardActions(onDone = {
				focusManager.clearFocus()
				onDone()
			}),
			decorationBox = { inner ->
				Box(contentAlignment = Alignment.CenterEnd) {
					if (value.isEmpty()) {
						Text(hint, style = MaterialTheme.typography.bodyLarge, color = lucent.ink3, textAlign = TextAlign.End)
					}
					inner()
				}
			},
		)
	}
}

@Composable
private fun SwitchRow(index: Int, count: Int, label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(index, count, onClick = if (enabled) ({ onChange(!checked) }) else null) {
		Text(
			label,
			style = MaterialTheme.typography.titleMedium,
			color = if (enabled) lucent.ink else lucent.ink3,
			modifier = Modifier.weight(1f),
		)
		Switch(checked = checked, onCheckedChange = null, enabled = enabled)
	}
}

/** Standard and Detailed as a connected button group, the way Appearance picks Dark or Light. */
@Composable
private fun DetailRow(index: Int, count: Int, detailed: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
	GroupRow(index, count) {
		Text(
			"Detail",
			style = MaterialTheme.typography.titleMedium,
			color = if (enabled) lucent.ink else lucent.ink3,
			modifier = Modifier.weight(1f),
		)
		Row(
			modifier = Modifier.width(DETAIL_WIDTH),
			horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
		) {
			listOf(false to "Standard", true to "Detailed").forEachIndexed { position, (value, label) ->
				ToggleButton(
					checked = detailed == value,
					onCheckedChange = { onChange(value) },
					enabled = enabled,
					shapes = if (position == 0) {
						ButtonGroupDefaults.connectedLeadingButtonShapes()
					} else {
						ButtonGroupDefaults.connectedTrailingButtonShapes()
					},
					modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
				) { Text(label) }
			}
		}
	}
}

@Composable
private fun ActionRow(
	index: Int,
	count: Int,
	@DrawableRes icon: Int,
	label: String,
	enabled: Boolean,
	onClick: () -> Unit,
	tint: Color = lucent.ink,
	busy: Boolean = false,
) {
	val color = if (enabled) tint else lucent.ink3
	GroupRow(index, count, onClick = if (enabled) onClick else null) {
		Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
		Text(label, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
		if (busy) LoadingIndicator(Modifier.size(32.dp))
	}
}

@Composable
private fun LineText(line: DiagText.Line) {
	when (line.tone) {
		DiagText.Tone.Problem -> ErrorLine(line.text)
		DiagText.Tone.Good -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
			Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.positive, modifier = Modifier.size(20.dp))
			Text(line.text, style = MaterialTheme.typography.labelLarge, color = lucent.ink)
		}
		DiagText.Tone.Neutral -> Text(line.text, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
	}
}

@Composable
private fun ForgetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
	DisposableEffect(Unit) {
		DiagScreen.presentation("forget", true)
		onDispose { DiagScreen.presentation("forget", false) }
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(DiagText.FORGET_TITLE) },
		text = { Text(DiagText.FORGET_MESSAGE) },
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

private const val STATUS_MILLIS = 3_000L
private val DETAIL_WIDTH = 224.dp

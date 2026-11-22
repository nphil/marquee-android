package com.nphil.marquee.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** A label with its value typed on the same row; [onFocus] reports focus after the field has had it once. */
@Composable
internal fun SettingsFieldRow(
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
	onDone: () -> Unit = {},
) {
	var focusedOnce by remember { mutableStateOf(false) }
	val focusManager = LocalFocusManager.current
	GroupRow(index, count) {
		Text(label, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
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
			singleLine = true,
			textStyle = MaterialTheme.typography.bodyLarge.copy(color = lucent.ink, textAlign = TextAlign.End),
			cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
			visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
			keyboardOptions = KeyboardOptions(
				capitalization = KeyboardCapitalization.None,
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
internal fun SettingsSwitchRow(index: Int, count: Int, label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsActionRow(
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

/** A small note under a group, in the quiet ink. */
@Composable
internal fun SettingsFootnote(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = lucent.ink2,
		modifier = modifier.padding(horizontal = Space.s4),
	)
}

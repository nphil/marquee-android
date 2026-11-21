package com.nphil.marquee.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nphil.marquee.ui.theme.lucent

/** Sign-out asks first: it deletes the sign-in and the server list. */
@Composable
fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Sign out of Plex?") },
		text = { Text("This tablet forgets the Plex sign-in and the server list. Settings and themes stay.") },
		confirmButton = {
			TextButton(
				onClick = {
					onDismiss()
					onConfirm()
				},
				colors = ButtonDefaults.textButtonColors(contentColor = lucent.danger),
			) { Text("Sign Out") }
		},
		dismissButton = {
			TextButton(
				onClick = onDismiss,
				colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
			) { Text("Cancel") }
		},
		titleContentColor = lucent.ink,
		textContentColor = lucent.ink2,
	)
}

package com.nphil.marquee.ui.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Says what a delete left undone. It hangs from the app, not from the page that started the delete: that page closes as
 * soon as the server has deleted the title, and the steps after it finish later. Retry is offered where asking again
 * may help.
 */
@Composable
internal fun DeletionFollowUpDialog(deletions: MediaDeletions) {
	val leftover by deletions.current.collectAsStateWithLifecycle()
	leftover?.let { shown ->
		AlertDialog(
			onDismissRequest = { deletions.dismiss(shown) },
			title = { Text("Deleted, but Not Everywhere") },
			text = { Text(DeleteWording.shortfall(shown.result, shown.item).joinToString("\n")) },
			confirmButton = {
				if (shown.canRetry) {
					TextButton(onClick = { deletions.retry(shown) }) { Text("Retry") }
				} else {
					TextButton(onClick = { deletions.dismiss(shown) }) { Text("OK") }
				}
			},
			dismissButton = if (shown.canRetry) ({ TextButton(onClick = { deletions.dismiss(shown) }) { Text("Not Now") } }) else null,
		)
	}
}

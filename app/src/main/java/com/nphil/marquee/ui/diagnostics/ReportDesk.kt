package com.nphil.marquee.ui.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.diagnostics.DiagScreenshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the report sheet opens with: where it was asked from, what was on screen, and a picture of that. */
class ReportRequest(val source: String, val screen: String, val screenshot: Bitmap?)

/**
 * Opens Report a Problem from anywhere: Diagnostic Logs, the floating button and, later, the player. The
 * screenshot is taken first, before the sheet covers the screen it is meant to show. Main thread only.
 */
object ReportDesk {
	private val mutableRequests = MutableSharedFlow<ReportRequest>(extraBufferCapacity = 1)
	private val mutableOpen = MutableStateFlow(false)
	private var capturing = false

	val requests: SharedFlow<ReportRequest> = mutableRequests.asSharedFlow()

	/** Whether the sheet is up; the floating button steps aside while it is. */
	val sheetOpen: StateFlow<Boolean> = mutableOpen.asStateFlow()

	/** Does nothing until a server and topic are saved: there is nowhere to send the report. */
	fun open(activity: ComponentActivity, source: String) {
		if (capturing || mutableOpen.value || Diag.settings.config.value?.configured != true) return
		capturing = true
		activity.lifecycleScope.launch {
			try {
				val screen = DiagScreen.summary
				val shot = DiagScreenshot.capture(activity)
				Diag.info(DiagCategory.Ui, "report.open", "from" to source, "screenshot" to (shot != null))
				mutableRequests.emit(ReportRequest(source, screen, shot))
			} finally {
				capturing = false
			}
		}
	}

	internal fun setSheetOpen(open: Boolean) {
		mutableOpen.value = open
	}
}

internal fun Context.findActivity(): ComponentActivity? {
	var context = this
	while (context is ContextWrapper) {
		if (context is ComponentActivity) return context
		context = context.baseContext
	}
	return null
}

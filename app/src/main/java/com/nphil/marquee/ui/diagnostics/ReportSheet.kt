package com.nphil.marquee.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagFacts
import com.nphil.marquee.diagnostics.DiagReportResult
import com.nphil.marquee.diagnostics.DiagReportText
import com.nphil.marquee.diagnostics.DiagScreen
import com.nphil.marquee.diagnostics.DiagScreenshot
import com.nphil.marquee.diagnostics.DiagText
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.components.GroupRow
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One send of a report: Idle until it starts, then Sending, then Sent or Failed; a failure can be sent again. */
internal sealed interface ReportPhase {
	data object Idle : ReportPhase
	data object Sending : ReportPhase
	data class Sent(val id: String, val attached: Boolean) : ReportPhase
	data class Failed(val text: String) : ReportPhase
}

/** Builds and sends a report on the app's own scope, so closing the sheet mid-send does not lose it. */
internal class ReportFlow(private val container: AppContainer, private val request: ReportRequest) {
	private val mutable = MutableStateFlow<ReportPhase>(ReportPhase.Idle)

	val phase: StateFlow<ReportPhase> = mutable.asStateFlow()
	val sending: Boolean get() = mutable.value is ReportPhase.Sending

	fun send(note: String, includeScreenshot: Boolean) {
		if (sending) return
		mutable.value = ReportPhase.Sending
		container.scope.launch {
			mutable.value = try {
				build(note, includeScreenshot)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				ReportPhase.Failed(DiagText.REPORT_FAILED)
			}
		}
	}

	private suspend fun build(note: String, includeScreenshot: Boolean): ReportPhase {
		val state = container.session.state.value
		val server = DiagReportText.server(state.server, state.server?.let { state.addresses[it.id] })
		val theme = DiagReportText.theme(container.settings.themeChoice.first())
		val config = Diag.settings.load()
		return withContext(Dispatchers.Default) {
			val jpeg = request.screenshot?.takeIf { includeScreenshot }?.let(DiagScreenshot::jpeg)
			val facts = DiagFacts.read(Diag.application, server, theme, config)
			val details = DiagReportText.details(facts, note, request.screen)
			when (val result = Diag.report(note.trim(), request.source, details, request.screen, jpeg)) {
				is DiagReportResult.Sent -> ReportPhase.Sent(result.id, result.attached)
				is DiagReportResult.Failed -> ReportPhase.Failed(result.text)
			}
		}
	}
}

/** Shows Report a Problem whenever [ReportDesk] is asked for it; placed once, inside the theme. */
@Composable
fun ReportSheetHost(container: AppContainer) {
	var request by remember { mutableStateOf<ReportRequest?>(null) }
	LaunchedEffect(Unit) { ReportDesk.requests.collect { request = it } }
	request?.let { open ->
		DisposableEffect(open) {
			ReportDesk.setSheetOpen(true)
			DiagScreen.presentation("report", true)
			onDispose {
				ReportDesk.setSheetOpen(false)
				DiagScreen.presentation("report", false)
			}
		}
		ReportSheet(container, open) { request = null }
	}
}

@Composable
private fun ReportSheet(container: AppContainer, request: ReportRequest, onDismiss: () -> Unit) {
	val flow = remember(request) { ReportFlow(container, request) }
	val phase by flow.phase.collectAsStateWithLifecycle()
	val config by Diag.settings.config.collectAsStateWithLifecycle()
	val sending = phase is ReportPhase.Sending
	val sent = phase is ReportPhase.Sent
	var note by rememberSaveable { mutableStateOf("") }
	var includeScreenshot by rememberSaveable { mutableStateOf(true) }
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !flow.sending })
	val scope = rememberCoroutineScope()
	val haptics = LocalHapticFeedback.current
	val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
	LaunchedEffect(phase) {
		when (phase) {
			is ReportPhase.Sent -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
			is ReportPhase.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
			else -> Unit
		}
	}
	ModalBottomSheet(
		onDismissRequest = { if (!flow.sending) onDismiss() },
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.surfaceContainer,
		properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !sending),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = Space.inset)
				.padding(bottom = Space.inset),
			verticalArrangement = Arrangement.spacedBy(Space.s4),
		) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				TextButton(
					onClick = close,
					enabled = !sending,
					modifier = Modifier.width(ACTION_WIDTH),
					colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
				) { Text(if (sent) "Done" else "Cancel") }
				Text(
					"Report a Problem",
					style = MaterialTheme.typography.titleLarge,
					color = lucent.ink,
					textAlign = TextAlign.Center,
					modifier = Modifier.weight(1f),
				)
				Box(Modifier.width(ACTION_WIDTH), contentAlignment = Alignment.CenterEnd) {
					if (!sent) Button(onClick = { flow.send(note, includeScreenshot) }, enabled = !sending) { Text("Send") }
				}
			}

			Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
				OutlinedTextField(
					value = note,
					onValueChange = { note = it },
					modifier = Modifier.fillMaxWidth(),
					enabled = !sending && !sent,
					placeholder = { Text(DiagText.NOTE_HINT) },
					minLines = 3,
					maxLines = 8,
					shape = RoundedCornerShape(Radius.control),
					colors = OutlinedTextFieldDefaults.colors(
						focusedTextColor = lucent.ink,
						unfocusedTextColor = lucent.ink,
						disabledTextColor = lucent.ink3,
						focusedPlaceholderColor = lucent.ink3,
						unfocusedPlaceholderColor = lucent.ink3,
						disabledPlaceholderColor = lucent.ink3,
						cursorColor = MaterialTheme.colorScheme.primary,
						focusedBorderColor = MaterialTheme.colorScheme.primary,
						unfocusedBorderColor = lucent.edgeRaised,
						disabledBorderColor = lucent.edge,
					),
				)
				Footnote(DiagText.NOTE_FOOTER)
			}

			request.screenshot?.let { bitmap ->
				Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
					val editable = !sending && !sent
					GroupRow(0, 1, onClick = if (editable) ({ includeScreenshot = !includeScreenshot }) else null) {
						Text(
							"Include Screenshot",
							style = MaterialTheme.typography.titleMedium,
							color = lucent.ink,
							modifier = Modifier.weight(1f),
						)
						Switch(checked = includeScreenshot, onCheckedChange = null, enabled = editable)
					}
					AnimatedVisibility(includeScreenshot) {
						Image(
							bitmap = remember(bitmap) { bitmap.asImageBitmap() },
							contentDescription = "Screenshot of the screen",
							contentScale = ContentScale.Fit,
							modifier = Modifier
								.fillMaxWidth()
								.heightIn(max = SHOT_HEIGHT)
								.clip(RoundedCornerShape(Radius.control)),
						)
					}
					Footnote(DiagText.SHOT_FOOTER)
				}
			}

			Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
				Text(
					"Sent with the report",
					style = MaterialTheme.typography.labelLarge,
					color = lucent.ink2,
					modifier = Modifier.padding(start = Space.s4, bottom = Space.s2),
				)
				val icons = listOf(R.drawable.ic_description, R.drawable.ic_tv, R.drawable.ic_settings)
				DiagText.sentWith.forEachIndexed { index, text ->
					GroupRow(index, DiagText.sentWith.size) {
						Icon(painterResource(icons[index]), contentDescription = null, tint = lucent.ink2, modifier = Modifier.size(20.dp))
						Text(text, style = MaterialTheme.typography.bodyLarge, color = lucent.ink, modifier = Modifier.weight(1f))
					}
				}
			}

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.semantics { liveRegion = LiveRegionMode.Polite },
			) {
				when (val shown = phase) {
					is ReportPhase.Idle -> config?.let { Footnote(DiagText.destination(it.topic, it.server)) }
					is ReportPhase.Sending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
						LoadingIndicator(Modifier.size(28.dp))
						Text("Sending…", style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
					}
					is ReportPhase.Sent -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
						Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = lucent.positive, modifier = Modifier.size(20.dp))
						Text(DiagText.sent(shown.id, shown.attached), style = MaterialTheme.typography.labelLarge, color = lucent.ink)
					}
					is ReportPhase.Failed -> ErrorLine(shown.text)
				}
			}
		}
	}
}

/** A small note under a group, in the quiet ink. */
@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = lucent.ink2,
		modifier = modifier.padding(horizontal = Space.s4),
	)
}

private val ACTION_WIDTH = 88.dp
private val SHOT_HEIGHT = 220.dp

package com.nphil.marquee.ui.player

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.playback.StatLine
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STATS_REFRESH_MS = 500L

/** How long the panel stays up, then it closes itself: a bright block of text does not sit on the picture. */
private const val STATS_DWELL_MS = 2 * 60_000L

/** How long Copy says "Copied". */
private const val COPIED_MS = 1_500L

/** The top bar's height, so the panel sits under it while the controls are up. */
private val BAR_CLEARANCE = 64.dp

/**
 * "Stats for Nerds" at the top-start over the picture: what plays and how playback keeps up, twice a second, in
 * monospace. Draws nothing unless [PlayerModel.showsStats]. Copy puts the same lines, as a report, on the clipboard.
 */
@Composable
fun StatsOverlay(model: PlayerModel, modifier: Modifier = Modifier) {
	if (!model.showsStats) return
	var stats by remember(model) { mutableStateOf(model.stats()) }
	LaunchedEffect(model) {
		while (true) {
			delay(STATS_REFRESH_MS)
			stats = model.stats()
		}
	}
	// Panel care: the timer starts when the panel is composed, so closing it and opening it again starts it afresh.
	LaunchedEffect(model) {
		delay(STATS_DWELL_MS)
		model.toggleStats()
	}
	val clipboard = LocalClipboard.current
	val scope = rememberCoroutineScope()
	var copied by remember { mutableStateOf(false) }
	val shape = RoundedCornerShape(Radius.control)
	Column(
		modifier
			.safeDrawingPadding()
			.padding(start = Space.s3, top = BAR_CLEARANCE)
			.widthIn(max = 380.dp)
			.background(lucent.scrim, shape)
			.border(1.dp, lucent.edge, shape)
			.padding(Space.s3),
		verticalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			stats.stream.forEach { StatRow(it) }
			if (stats.stream.isNotEmpty() && stats.health.isNotEmpty()) Spacer(Modifier.height(Space.s2))
			stats.health.forEach { StatRow(it) }
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			TextButton(
				onClick = {
					scope.launch {
						clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Marquee playback stats", model.statsReport())))
						copied = true
						delay(COPIED_MS)
						copied = false
					}
				},
			) { Text(if (copied) "Copied" else "Copy") }
			TextButton(onClick = model::toggleStats) { Text("Close") }
		}
	}
}

@Composable
private fun StatRow(line: StatLine) {
	val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
		Text(line.label, style = style, color = lucent.ink2, modifier = Modifier.weight(0.45f))
		Text(line.value, style = style, color = lucent.ink, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
	}
}

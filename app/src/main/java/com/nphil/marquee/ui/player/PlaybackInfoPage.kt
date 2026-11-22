package com.nphil.marquee.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.nphil.marquee.playback.PlayerModel
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** How the title plays and why, and the file's tracks: the sections of [PlayerModel.info] as titles over rows of text. */
@Composable
fun PlaybackInfoPage(model: PlayerModel, modifier: Modifier = Modifier) {
	// Read once when the page opens: it describes the stream that is playing, not a live meter.
	val sections = remember(model) { model.info() }
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		sections.forEach { section ->
			Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
				Text(
					section.title,
					style = MaterialTheme.typography.titleSmall,
					color = lucent.ink2,
					modifier = Modifier.padding(horizontal = Space.s3),
				)
				section.lines.forEach { line ->
					Row(Modifier.fillMaxWidth().padding(horizontal = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
						Text(line.label, style = MaterialTheme.typography.bodyMedium, color = lucent.ink2, modifier = Modifier.weight(0.4f))
						Text(
							line.value,
							style = MaterialTheme.typography.bodyMedium,
							color = lucent.ink,
							textAlign = TextAlign.End,
							modifier = Modifier.weight(0.6f),
						)
					}
				}
			}
		}
	}
}

package com.nphil.marquee.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.FindBanner
import com.nphil.marquee.playback.PlayerSubtitleFinder
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

@Composable
internal fun SubtitleSearchBanner(finder: PlayerSubtitleFinder, modifier: Modifier = Modifier) {
	val banner = finder.banner ?: return
	Row(
		modifier.padding(horizontal = Space.s4).widthIn(max = 380.dp)
			.background(lucent.canvas, RoundedCornerShape(Radius.row)).padding(Space.s3)
			.semantics { liveRegion = LiveRegionMode.Polite },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s3),
	) {
		when (banner.tone) {
			FindBanner.Tone.Busy -> CircularProgressIndicator(Modifier.size(22.dp), color = lucent.info, strokeWidth = 2.dp)
			FindBanner.Tone.Found -> Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(22.dp), tint = lucent.positive)
			FindBanner.Tone.Missing -> Icon(painterResource(R.drawable.ic_error), null, Modifier.size(22.dp), tint = lucent.warning)
		}
		Column(Modifier.weight(1f, fill = false)) {
			Text(banner.title, color = lucent.ink, style = MaterialTheme.typography.titleSmall)
			Text(banner.detail, color = lucent.ink2, style = MaterialTheme.typography.bodySmall)
		}
	}
}

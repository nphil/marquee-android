package com.nphil.marquee.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nphil.marquee.ui.servers.ContentWidth
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The licence of the bundled Manrope, which the OFL asks to travel with the font. */
@Composable
fun LicencesPage(contentPadding: PaddingValues) {
	val context = LocalContext.current
	val text by produceState<String?>(null) {
		value = withContext(Dispatchers.IO) {
			context.assets.open("licences/Manrope-OFL.txt").use { it.readBytes().decodeToString() }
		}
	}
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(Space.s4),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item(key = "font") {
			Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
				Text("Manrope", style = MaterialTheme.typography.titleMedium, color = lucent.ink)
				Text("SIL Open Font License 1.1", style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
			}
		}
		text?.let { licence ->
			item(key = "text") {
				Text(
					licence,
					style = MaterialTheme.typography.bodySmall,
					color = lucent.ink2,
					modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
				)
			}
		}
	}
}

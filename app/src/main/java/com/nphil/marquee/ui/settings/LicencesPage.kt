package com.nphil.marquee.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import com.nphil.marquee.ui.components.BrowseList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.nphil.marquee.R
import com.nphil.marquee.ffmpeg.BuildConfig as FfmpegBuild
import com.nphil.marquee.ui.components.GroupGap
import com.nphil.marquee.ui.servers.ContentWidth
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FFMPEG_NOTICE =
	"Marquee decodes audio the tablet cannot (AC3, E-AC3, DTS, TrueHD and a few more) with FFmpeg, through Media3's " +
		"FFmpeg extension (Apache License 2.0). On a tablet with no MPEG-2 decoder it decodes that video with FFmpeg too, " +
		"through Marquee's own player code. The FFmpeg source is unmodified and built with only its LGPL parts, " +
		"as separate libraries (libavcodec, libavutil, libswresample) that a copy rebuilt from the same source can replace."

/**
 * What the app owes the parts it ships. The OFL asks Manrope's licence to travel with the font; the LGPL asks for its
 * text, notice that FFmpeg is used, and a way to the source. Both texts are bundled assets.
 */
@Composable
fun LicencesPage(contentPadding: PaddingValues) {
	val context = LocalContext.current
	val fontLicence by licenceText("Manrope-OFL.txt")
	val ffmpegLicence by licenceText("FFmpeg-LGPL-2.1.txt")
	val airkastLicence by licenceText("Airkast-Apache-2.0.txt")
	val ffmpegLinks = listOf(
		"FFmpeg ${FfmpegBuild.FFMPEG_VERSION} source code" to "https://ffmpeg.org/releases/ffmpeg-${FfmpegBuild.FFMPEG_VERSION}.tar.xz",
		"Build script and extension source" to "https://github.com/nphil/marquee-android/tree/main/ffmpeg",
	)
	BrowseList(
		modifier = Modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(Space.s4),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item(key = "font") { Licence("Manrope", "SIL Open Font License 1.1") }
		fontLicence?.let { licence -> item(key = "font-text") { LicenceText(licence) } }
		item(key = "ffmpeg") {
			Licence("FFmpeg", "GNU LGPL 2.1 or later · version ${FfmpegBuild.FFMPEG_VERSION}") {
				Text(FFMPEG_NOTICE, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
				Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
					ffmpegLinks.forEachIndexed { index, (label, url) ->
						SettingsActionRow(index, ffmpegLinks.size, R.drawable.ic_open_in_new, label, enabled = true, onClick = { open(context, url) })
					}
				}
			}
		}
		ffmpegLicence?.let { licence -> item(key = "ffmpeg-text") { LicenceText(licence) } }
		item(key = "airkast") { Licence("Airkast", "Apache License 2.0") }
		airkastLicence?.let { licence -> item(key = "airkast-text") { LicenceText(licence) } }
	}
}

/** A component's name and the terms it is under, then whatever else [content] says about it. */
@Composable
private fun Licence(name: String, terms: String, content: @Composable ColumnScope.() -> Unit = {}) {
	Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
		Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			Text(name, style = MaterialTheme.typography.titleMedium, color = lucent.ink)
			Text(terms, style = MaterialTheme.typography.bodySmall, color = lucent.ink2)
		}
		content()
	}
}

@Composable
private fun LicenceText(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = lucent.ink2,
		modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
	)
}

/** The text of the bundled licence in [file], read off the main thread; null until it is. */
@Composable
private fun licenceText(file: String): State<String?> {
	val context = LocalContext.current
	return produceState<String?>(null, file) {
		value = withContext(Dispatchers.IO) {
			context.assets.open("licences/$file").use { it.readBytes().decodeToString() }
		}
	}
}

private fun open(context: Context, url: String) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
	} catch (_: ActivityNotFoundException) {
		// No browser on this tablet: the link has nothing to open it.
	}
}

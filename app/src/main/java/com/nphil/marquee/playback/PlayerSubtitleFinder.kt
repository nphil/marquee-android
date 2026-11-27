package com.nphil.marquee.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.FindBanner
import com.nphil.marquee.core.MarqueeCore
import com.nphil.marquee.core.PlayTracks
import com.nphil.marquee.core.SubtitleSource
import com.nphil.marquee.core.findSubtitles
import com.nphil.marquee.core.findSubtitlesStatus
import com.nphil.marquee.data.SubtitleServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Stable
internal class PlayerSubtitleFinder(private val scope: CoroutineScope, private val core: MarqueeCore, private val services: SubtitleServices) {
	var banner by mutableStateOf<FindBanner?>(null)
		private set
	val busy: Boolean get() = banner?.tone == FindBanner.Tone.Busy
	private var task: Job? = null
	var waitingFor by mutableStateOf<SubtitleSource?>(null)
		private set

	fun find(source: SubtitleSource, checkOnly: Boolean, apply: suspend (PlayTracks, Int?) -> Boolean) {
		if (busy) return
		task?.cancel()
		banner = FindBanner.busy(if (checkOnly) "Checking Plex for the last file…" else if (services.bazarr.value == null) "Asking Plex…" else "Asking Bazarr…")
		task = scope.launch {
			banner = try {
				val reply = coroutineScope {
					val request = UUID.randomUUID().toString()
					val finding = async { core.findSubtitles(source, request, if (checkOnly) null else services.bazarrLink(), checkOnly) }
					val progress = launch {
						while (true) {
							delay(250)
							try {
								core.findSubtitlesStatus(source.session, request)?.let { banner = it }
							} catch (_: CoreException) {
								// The result call, not a progress read, decides how the search ended.
								break
							}
						}
					}
					try { finding.await() } finally { progress.cancel() }
				}
				waitingFor = source.takeIf { reply.awaitingFile }
				if (reply.trackId != null && reply.tracks != null) {
					banner = FindBanner.busy("Loading the new subtitle…")
					if (apply(reply.tracks, reply.trackId)) reply.banner
					else FindBanner(FindBanner.Tone.Missing, "The new subtitles didn't load", "Find again for another file.")
				} else {
					reply.tracks?.let { apply(it, null) }
					reply.banner
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				FindBanner(FindBanner.Tone.Missing, "Couldn't find subtitles", "Try again when Plex is available.")
			}
			delay(6_000)
			banner = null
		}
	}

	fun cancel() {
		task?.cancel()
		task = null
		waitingFor = null
		banner = null
	}
}

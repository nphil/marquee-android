package com.nphil.marquee.television

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Window
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlin.math.abs
import kotlin.math.roundToInt

/** Media3 votes for seamless changes. An HDMI mode change needs the viewer's non-seamless opt-in. */
@OptIn(UnstableApi::class)
@Composable
internal fun MatchFilmRate(window: Window, engine: ExoPlayer?, sourceFps: Double?) {
    if (engine == null || Build.VERSION.SDK_INT < 31) return
    LifecycleResumeEffect(window, engine, sourceFps) {
        val display = window.decorView.display
        val manager = window.context.getSystemService(DisplayManager::class.java)
        val previous = window.attributes.preferredDisplayModeId
        var requested = previous
        fun match(decoded: Float?) {
            if (display == null || manager.matchContentFrameRateUserPreference != DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) return
            // Some containers round 24000/1001 to 24. Keep the precise source rate when the decoder agrees.
            val fps = sourceFps?.takeIf { decoded == null || decoded <= 0 || abs(it - decoded) < .04 }
                ?: decoded?.toDouble() ?: return
            if (fps !in 10.0..240.0) return
            val current = display.mode
            var best = previous
            var cost = Double.MAX_VALUE
            for (mode in display.supportedModes) {
                if (mode.physicalWidth != current.physicalWidth || mode.physicalHeight != current.physicalHeight) continue
                val multiple = (mode.refreshRate / fps).roundToInt()
                if (multiple < 1) continue
                val error = abs(mode.refreshRate / multiple - fps)
                if (error > .015) continue
                val score = multiple + error
                if (score < cost) { best = mode.modeId; cost = score }
            }
            if (requested != best) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best }; requested = best
            }
        }
        val listener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) = match(format.frameRate)
        }
        engine.addAnalyticsListener(listener)
        match(engine.videoFormat?.frameRate)
        onPauseOrDispose {
            engine.removeAnalyticsListener(listener)
            if (requested != previous && window.attributes.preferredDisplayModeId == requested) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = previous }
            }
        }
    }
}

package com.nphil.marquee.television

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import com.nphil.marquee.data.SubtitleLook
import com.nphil.marquee.ui.player.TvCaptions

/** Only in the offline emulator APK. The colour of each decoded cue identifies its ink in screencaps. */
class CaptionReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val look = SubtitleLook(atTop = intent.getBooleanExtra("top", false), softens = false, opacity = 100)
        val controls = intent.getBooleanExtra("hud", false)
        val bitmap = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.CYAN)
            Canvas(it).drawText("EXIT →", 10f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f })
        }
        val cues = buildList {
            addAll(decoded(SubripParser(), "1\n00:00:00,000 --> 00:00:05,000\nThe last train.\n"))
            addAll(decoded(SubripParser(), "1\n00:00:00,000 --> 00:00:05,000\n{\\an9}<font color=\"#ffff00\">North exit</font>\n"))
            add(Cue.Builder().setText(ink("Platform 2", android.graphics.Color.GREEN))
                .setPosition(.1f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build())
            add(Cue.Builder().setBitmap(bitmap).setPosition(.12f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(.22f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.12f).build())
            if (intent.getBooleanExtra("vertical", false)) addAll(decoded(WebvttParser(),
                "WEBVTT\n\n00:00:00.000 --> 00:00:05.000 vertical:rl line:65% position:24%,line-left\n<c.magenta>終電時刻</c>\n"))
        }
        setContent { TvCaptions(cues, look, controls, Modifier.fillMaxSize().background(Color.Black)) }
    }

    private fun decoded(parser: SubtitleParser, input: String): List<Cue> = buildList {
        parser.parse(input.toByteArray(), SubtitleParser.OutputOptions.allCues()) { addAll(it.cues) }
    }

    private fun ink(text: String, color: Int) = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

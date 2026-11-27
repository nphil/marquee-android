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
            add(Cue.Builder().setText("The last train.").build())
            add(Cue.Builder().setText(ink("Platform 2", android.graphics.Color.GREEN))
                .setPosition(.1f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build())
            add(Cue.Builder().setBitmap(bitmap).setPosition(.12f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(.22f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.12f).build())
            if (intent.getBooleanExtra("vertical", false)) add(Cue.Builder().setText(ink("終電時刻", android.graphics.Color.MAGENTA))
                .setVerticalType(Cue.VERTICAL_TYPE_RL).setPosition(.24f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build())
        }
        setContent { TvCaptions(cues, look, controls, Modifier.fillMaxSize().background(Color.Black)) }
    }

    private fun ink(text: String, color: Int) = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

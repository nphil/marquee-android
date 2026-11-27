package com.nphil.marquee.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.nphil.marquee.BuildConfig

/** A single-line field has no vertical caret movement. Return those remote keys to the form once the IME closes. */
@Composable
internal fun Modifier.tvFieldNavigation(): Modifier {
    if (!BuildConfig.TV_CONTROLS) return this
    val focus = LocalFocusManager.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || ime.getBottom(density) > 0) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            else -> false
        }
    }
}

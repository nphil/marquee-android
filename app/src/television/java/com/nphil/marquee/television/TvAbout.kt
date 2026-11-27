package com.nphil.marquee.television

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.nphil.marquee.core.PlexItem
import com.nphil.marquee.core.versionLabel
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

@Composable internal fun TvAbout(item: PlexItem) {
    val sections = remember(item) { buildList {
        fun addText(label: String, value: String?) { if (!value.isNullOrBlank()) add(label to value) }
        addText("Synopsis", item.summary)
        addText("Directed by", item.directors.joinToString())
        addText("Written by", item.writers.joinToString())
        addText("Genres", item.genres.joinToString())
        addText("Released", item.originallyAvailableAt ?: item.year?.toString())
        addText("Studio", item.studio)
        addText("Content rating", item.contentRating)
        val parts = item.media.flatMap { it.parts }
        fun languages(type: Int) = parts.flatMap { it.streams }.asSequence().filter { it.streamType == type }
            .mapNotNull { it.language ?: it.languageTag ?: it.languageCode }.distinct().joinToString()
        addText("Audio languages", languages(2))
        addText("Subtitle languages", languages(3))
        addText("In this library", item.media.joinToString("\n") { it.versionLabel })
    } }
    val focus = remember { FocusRequester() }; val scroll = rememberScrollState(); val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxSize().background(lucent.canvas).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(.7f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("About", color = lucent.accentText, style = MaterialTheme.typography.titleMedium)
            Text(item.title, color = lucent.ink, style = MaterialTheme.typography.headlineLarge)
            item.tagline?.takeIf { it.isNotBlank() }?.let { Text(it, color = lucent.ink2) }
        }
        Column(Modifier.weight(1.3f).fillMaxHeight().focusRequester(focus).onPreviewKeyEvent { key ->
            val direction = when (key.key) { Key.DirectionDown -> 1; Key.DirectionUp -> -1; else -> return@onPreviewKeyEvent false }
            if (key.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * scroll.viewportSize * .45f) }
            true
        }.focusable().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (sections.isEmpty()) Text("No additional details available.", color = lucent.ink2)
            for ((label, value) in sections) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = lucent.accentText, style = MaterialTheme.typography.labelLarge)
                Text(value, color = lucent.ink, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    LaunchedEffect(item.ratingKey) { focus.requestFocus() }
}

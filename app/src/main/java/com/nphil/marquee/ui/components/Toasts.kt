package com.nphil.marquee.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One message at the bottom of the screen. */
@Immutable
class Toast internal constructor(
	val id: Long,
	val message: String,
	val undo: (() -> Unit)?,
	val commit: (() -> Unit)?,
)

/**
 * The one message a screen shows after a change, as the iPhone app's ToastCenter does: three seconds, or six when
 * it offers Undo. A change that cannot be taken back after the message is gone hands its action as [commit], which
 * runs when the message goes, however it goes, unless Undo was tapped. Used on the main thread.
 */
class ToastCenter(private val scope: CoroutineScope) {
	private val mutable = MutableStateFlow<Toast?>(null)
	val current: StateFlow<Toast?> = mutable.asStateFlow()
	private var timer: Job? = null
	private var lastId = 0L

	/** Shows [message]; a message still showing is finished first, so its [commit] is not lost. */
	fun show(message: String, undo: (() -> Unit)? = null, commit: (() -> Unit)? = null) {
		finish()
		Diag.info(DiagCategory.Ui, "toast", "message" to message, "undo" to (undo != null))
		val toast = Toast(++lastId, message, undo, commit)
		mutable.value = toast
		timer = scope.launch {
			delay(if (undo == null) SHORT_MS else LONG_MS)
			if (mutable.value?.id == toast.id) finish()
		}
	}

	/** Takes the change back, and drops its [Toast.commit]. */
	fun undo() {
		val toast = mutable.value ?: return
		clear()
		toast.undo?.invoke()
	}

	/** Ends the message now and does what it waited to do. The app calls this when it stops. */
	fun finish() {
		val toast = mutable.value ?: return
		clear()
		toast.commit?.invoke()
	}

	private fun clear() {
		timer?.cancel()
		timer = null
		mutable.value = null
	}

	private companion object {
		const val SHORT_MS = 3_000L
		const val LONG_MS = 6_000L
	}
}

/** The bottom of the screen's message, above the system bars: a sheet-coloured bar with Undo in accent. */
@Composable
fun ToastHost(center: ToastCenter, modifier: Modifier = Modifier) {
	val toast by center.current.collectAsStateWithLifecycle()
	val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
	Box(
		modifier = modifier
			.fillMaxWidth()
			.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
			.padding(Space.inset),
		contentAlignment = Alignment.BottomCenter,
	) {
		AnimatedContent(
			targetState = toast,
			transitionSpec = {
				(fadeIn(effects) + slideInVertically(spatial) { it / 2 }) togetherWith
					(fadeOut(effects) + slideOutVertically(spatial) { it / 2 })
			},
			contentKey = { it?.id },
			label = "toast",
		) { shown ->
			if (shown != null) ToastBar(shown, onUndo = center::undo)
		}
	}
}

@Composable
private fun ToastBar(toast: Toast, onUndo: () -> Unit) {
	val shape = RoundedCornerShape(Radius.row)
	Row(
		modifier = Modifier
			.widthIn(max = 560.dp)
			.fillMaxWidth()
			.background(lucent.sheet, shape)
			.border(1.dp, lucent.edgeRaised, shape)
			.heightIn(min = Size.target)
			.padding(start = Space.s4, end = if (toast.undo != null) Space.s1 else Space.s4, top = Space.s1, bottom = Space.s1)
			.semantics { liveRegion = LiveRegionMode.Polite },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.s2),
	) {
		Text(
			toast.message,
			style = MaterialTheme.typography.bodyLarge,
			color = lucent.ink,
			modifier = Modifier.weight(1f).padding(vertical = Space.s2),
		)
		if (toast.undo != null) TextAction("Undo", onUndo)
	}
}

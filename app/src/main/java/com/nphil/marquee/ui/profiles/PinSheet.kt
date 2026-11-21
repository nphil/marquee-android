package com.nphil.marquee.ui.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.CoreException
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.session.AppSession
import com.nphil.marquee.ui.components.Avatar
import com.nphil.marquee.ui.components.ErrorLine
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent
import kotlinx.coroutines.launch

/** Plex Home PINs are 4 digits, so the fourth one submits. */
private const val PIN_LENGTH = 4

/** A protected profile's PIN in a sheet. A wrong PIN clears the dots, shakes them and says so. */
@Composable
fun PinSheet(profile: PlexHomeUser, session: AppSession, onDismiss: () -> Unit) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val scope = rememberCoroutineScope()
	val haptics = LocalHapticFeedback.current
	var digits by remember { mutableStateOf("") }
	var checking by remember { mutableStateOf(false) }
	var message by remember { mutableStateOf<String?>(null) }
	val shake = remember { Animatable(0f) }

	fun refuse(text: String) {
		haptics.performHapticFeedback(HapticFeedbackType.Reject)
		message = text
		digits = ""
		// As the system's own lock screen does; with animations off it ends at rest at once.
		scope.launch {
			shake.animateTo(0f, keyframes {
				durationMillis = 320
				-10f at 50
				10f at 120
				-7f at 190
				7f at 260
			})
		}
	}

	fun submit(pin: String) {
		checking = true
		scope.launch {
			try {
				session.switchUser(profile, pin)
				haptics.performHapticFeedback(HapticFeedbackType.Confirm)
				sheetState.hide()
				onDismiss()
			} catch (e: CoreException) {
				refuse(if (e.kind == CoreException.Kind.ProfilePinRejected) "Wrong PIN" else "Couldn't switch. Check the connection.")
			}
			checking = false
		}
	}

	fun add(digit: Int) {
		if (checking || digits.length >= PIN_LENGTH) return
		message = null
		digits += digit
		if (digits.length == PIN_LENGTH) submit(digits)
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(Space.inset),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Space.s4),
		) {
			Avatar(profile, size = Size.rowMin)
			Text(profile.title, style = MaterialTheme.typography.headlineSmall, color = lucent.ink)
			Text("Enter PIN", style = MaterialTheme.typography.titleMedium, color = lucent.ink2)
			PinDots(filled = digits.length, modifier = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) })
			// Holds its height while empty, so the keys never move under a finger between tries.
			Box(Modifier.heightIn(min = Size.target), contentAlignment = Alignment.Center) {
				val shown = message
				when {
					checking -> LoadingIndicator(modifier = Modifier.size(24.dp))
					shown != null -> ErrorLine(shown)
				}
			}
			Keypad(
				enabled = !checking,
				onDigit = ::add,
				onDelete = { if (digits.isNotEmpty()) digits = digits.dropLast(1) },
			)
		}
	}
}

/** One dot per digit, filled as they are typed. Outlines in ink2 keep the empty ones visible on any theme. */
@Composable
private fun PinDots(filled: Int, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier.semantics {
			contentDescription = "PIN"
			stateDescription = "$filled of $PIN_LENGTH digits"
		},
		horizontalArrangement = Arrangement.spacedBy(Space.s4),
	) {
		repeat(PIN_LENGTH) { index ->
			val on = index < filled
			Box(
				Modifier
					.size(Space.s3)
					.background(if (on) lucent.ink else lucent.canvas.copy(alpha = 0f), CircleShape)
					.border(BorderStroke(1.5.dp, if (on) lucent.ink else lucent.ink2), CircleShape),
			)
		}
	}
}

/** Digits in the telephone layout, with Delete in the bottom right. */
@Composable
private fun Keypad(enabled: Boolean, onDigit: (Int) -> Unit, onDelete: () -> Unit) {
	val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
	Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
		rows.forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
				row.forEach { digit -> Key(enabled, onClick = { onDigit(digit) }) { Digit(digit) } }
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
			Spacer(Modifier.size(KeySize))
			Key(enabled, onClick = { onDigit(0) }) { Digit(0) }
			Key(enabled, onClick = onDelete) {
				Icon(painterResource(R.drawable.ic_backspace), contentDescription = "Delete", tint = lucent.ink)
			}
		}
	}
}

private val KeySize = Size.target + Space.s4

@Composable
private fun Key(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
	Surface(
		onClick = onClick,
		enabled = enabled,
		shape = CircleShape,
		color = lucent.glassRaised,
		contentColor = lucent.ink,
		border = BorderStroke(1.dp, lucent.edge),
		modifier = Modifier.size(KeySize),
	) {
		Box(contentAlignment = Alignment.Center) { content() }
	}
}

@Composable
private fun Digit(digit: Int) {
	Text(digit.toString(), style = MaterialTheme.typography.headlineSmall, color = lucent.ink)
}

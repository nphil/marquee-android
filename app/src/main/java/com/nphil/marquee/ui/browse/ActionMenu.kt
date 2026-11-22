package com.nphil.marquee.ui.browse

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.theme.Radius
import com.nphil.marquee.ui.theme.lucent

/** One line of an [ActionMenu]. A [destructive] one is drawn in the danger colour. */
@Immutable
class MenuAction(
	@DrawableRes val icon: Int,
	val label: String,
	val destructive: Boolean = false,
	val onClick: () -> Unit,
)

/** What a long press offers, as a menu over the thing pressed. Choosing a line closes it first. */
@Composable
fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>, modifier: Modifier = Modifier) {
	DropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismiss,
		modifier = modifier,
		shape = RoundedCornerShape(Radius.row),
		containerColor = lucent.sheet,
		tonalElevation = 0.dp,
		border = BorderStroke(1.dp, lucent.edge),
	) {
		for (action in actions) {
			DropdownMenuItem(
				text = {
					Text(
						action.label,
						style = MaterialTheme.typography.bodyLarge,
						color = if (action.destructive) lucent.danger else lucent.ink,
					)
				},
				leadingIcon = {
					Icon(painterResource(action.icon), contentDescription = null, tint = if (action.destructive) lucent.danger else lucent.ink2)
				},
				onClick = {
					onDismiss()
					action.onClick()
				},
			)
		}
	}
}

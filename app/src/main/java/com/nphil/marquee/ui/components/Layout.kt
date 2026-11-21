package com.nphil.marquee.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nphil.marquee.ui.theme.Space

/** A screen's padding plus Lucent's screen inset, for lists that scroll under the bars. */
@Composable
fun PaddingValues.plusInset(): PaddingValues {
	val direction = LocalLayoutDirection.current
	return PaddingValues(
		start = calculateStartPadding(direction) + Space.inset,
		end = calculateEndPadding(direction) + Space.inset,
		top = calculateTopPadding() + Space.s2,
		bottom = calculateBottomPadding() + Space.inset,
	)
}

/** A row's glyph on the accent's container, in Material's nine-sided cookie. */
@Composable
fun IconBadge(@DrawableRes icon: Int) {
	Box(
		modifier = Modifier
			.size(40.dp)
			.background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painterResource(icon),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.size(20.dp),
		)
	}
}

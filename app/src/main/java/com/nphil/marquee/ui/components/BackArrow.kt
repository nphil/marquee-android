package com.nphil.marquee.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.nphil.marquee.R

/** A top bar's navigation icon: the back arrow, running [onBack]. */
@Composable
fun BackArrow(onBack: () -> Unit) {
	IconButton(onClick = onBack) {
		Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
	}
}

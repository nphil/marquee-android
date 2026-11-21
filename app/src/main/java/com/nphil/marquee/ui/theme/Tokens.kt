package com.nphil.marquee.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lucent's corner radii (DESIGN §4.2). */
object Radius {
	val sheet = 28.dp
	val card = 24.dp

	/** Art whose short side is 96 dp or more. */
	val tile = 20.dp
	val row = 18.dp

	/** Art whose short side is under 96 dp, and text fields. */
	val control = 14.dp

	/** The radius of a shape inset inside another. */
	fun inner(outer: Dp, inset: Dp): Dp = maxOf(outer - inset, 6.dp)
}

/** Lucent's spacing steps. */
object Space {
	val s1 = 4.dp
	val s2 = 8.dp
	val s3 = 12.dp
	val s4 = 16.dp
	val s5 = 20.dp
	val s6 = 24.dp
	val s7 = 32.dp
	val s8 = 40.dp

	/** Screen inset, added to the system bars' insets, never instead of them. */
	val inset = 16.dp
}

object Size {
	val target = 48.dp
	val rowMin = 56.dp

	/** Minimum theme-tile width in an adaptive grid. */
	val gridMin = 104.dp
}

/** Material's shape scale on Lucent's radii. */
val LucentShapes = Shapes(
	extraSmall = RoundedCornerShape(Radius.control),
	small = RoundedCornerShape(Radius.control),
	medium = RoundedCornerShape(Radius.row),
	large = RoundedCornerShape(Radius.card),
	extraLarge = RoundedCornerShape(Radius.sheet),
)

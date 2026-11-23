package com.nphil.marquee.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.nphil.marquee.R
import com.nphil.marquee.core.PlexHomeUser
import com.nphil.marquee.diagnostics.Diag
import com.nphil.marquee.diagnostics.DiagCategory
import com.nphil.marquee.ui.components.Avatar
import com.nphil.marquee.ui.theme.Size
import com.nphil.marquee.ui.theme.Space
import com.nphil.marquee.ui.theme.lucent

/** One place the sidebar can go. [icon] is drawn when it is not selected and [selectedIcon] when it is. */
@Immutable
class SidebarDestination(
	val key: String,
	val label: String,
	@DrawableRes val icon: Int,
	@DrawableRes val selectedIcon: Int,
)

/**
 * The tablet's navigation sidebar: an icon rail that opens into icons with labels. The Marquee mark at the top is
 * the only control for opening and closing it; the caller owns [expanded] and flips it in [onToggle].
 *
 * Place it as the first child of a full-size Row with the content beside it. It draws under the system bars and
 * applies the start, top and bottom bar and cutout insets itself, so give it none and keep those insets off the
 * content's start edge. Its width is 72 dp rail or 236 dp open, plus the start inset.
 *
 * [destinations] scroll; [onRemote], when present, and Settings stay pinned to the bottom. [selected] is a [SidebarDestination.key]
 * or [SETTINGS_KEY].
 */
@Composable
fun MarqueeSidebar(
	destinations: List<SidebarDestination>,
	selected: String,
	onSelect: (String) -> Unit,
	expanded: Boolean,
	onToggle: () -> Unit,
	settingsProfile: PlexHomeUser?,
	modifier: Modifier = Modifier,
	onRemote: (() -> Unit)? = null,
	bigScreen: BigScreenEntry? = null,
) {
	val theme = lucent
	// Both animations are read only in layout and draw below, so a toggle never recomposes the items. The
	// rail starts at its target, so a restored sidebar does not animate on its first frame.
	val width = animateDpAsState(
		targetValue = if (expanded) ExpandedWidth else CollapsedWidth,
		animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
		label = "sidebar-width",
	)
	val labels = animateFloatAsState(
		targetValue = if (expanded) 1f else 0f,
		// Labels wait for the pane to open and leave before it closes.
		animationSpec = if (expanded) tween(180, delayMillis = 60) else tween(70),
		label = "sidebar-labels",
	)
	val labelAlpha = remember(labels) { { labels.value } }
	val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
		.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)

	Column(
		modifier
			.testTag(SidebarTags.Root)
			.fillMaxHeight()
			.background(theme.sheet)
			.drawBehind {
				val edge = 1.dp.toPx()
				val x = if (layoutDirection == LayoutDirection.Ltr) size.width - edge else 0f
				drawRect(theme.edge, Offset(x, 0f), size.copy(width = edge))
			}
			// Outside the animated width, so the insets add to it and the icons keep their place while it moves.
			.windowInsetsPadding(insets)
			.layout { measurable, constraints ->
				val w = constraints.constrainWidth(width.value.roundToPx())
				val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
				layout(w, placeable.height) { placeable.place(0, 0) }
			}
			.padding(horizontal = Space.s3, vertical = Space.inset),
	) {
		SidebarBrand(expanded, onToggle, labelAlpha)
		Spacer(Modifier.height(Space.s4))
		Column(
			Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.selectableGroup(),
			verticalArrangement = Arrangement.spacedBy(Space.s1),
		) {
			for (destination in destinations) {
				val active = destination.key == selected
				SidebarItem(destination.key, destination.label, active, labelAlpha, onSelect) { tint ->
					Icon(
						painterResource(if (active) destination.selectedIcon else destination.icon),
						contentDescription = null,
						modifier = Modifier.size(IconSize),
						tint = tint,
					)
				}
			}
		}
		Spacer(Modifier.height(Space.s2))
		Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
			if (bigScreen != null) {
				SidebarItem("big-screen", bigScreen.label, false, labelAlpha, { bigScreen.onClick() }, role = Role.Button) { tint ->
					Icon(painterResource(bigScreen.icon), contentDescription = null, modifier = Modifier.size(IconSize), tint = tint)
				}
			} else if (onRemote != null) {
				SidebarItem("tv-remote", "TV Remote", false, labelAlpha, { onRemote() }, role = Role.Button) { tint ->
					Icon(painterResource(R.drawable.ic_tv_remote), contentDescription = null, modifier = Modifier.size(IconSize), tint = tint)
				}
			}
			SidebarItem(SETTINGS_KEY, SettingsLabel, selected == SETTINGS_KEY, labelAlpha, onSelect) {
				SettingsAvatar(settingsProfile)
			}
		}
	}
}

/** The connected display's remote button, which takes the place of the TV remote's while a display is connected. */
class BigScreenEntry(val label: String, @androidx.annotation.DrawableRes val icon: Int, val onClick: () -> Unit)

/** What the UI tests find the sidebar's parts by. */
internal object SidebarTags {
	const val Root = "sidebar"
	const val Brand = "sidebar-brand"

	fun item(key: String) = "sidebar-item-$key"
}

internal val CollapsedWidth = 72.dp
internal val ExpandedWidth = 236.dp

/**
 * The glyph is about 35 dp wide in it, a little bigger than the icons below, and the slabs still move inside the
 * 48 dp slot.
 */
private val MarkSize = 40.dp

private val IconSize = 24.dp

/** How far a label travels in from the start edge while it fades in. */
private val LabelSlide = 8.dp

private const val BrandName = "Marquee"
private const val SettingsLabel = "Settings"

@Composable
internal fun CompactSettingsButton(profile: PlexHomeUser?, onClick: () -> Unit) {
	Box(
		Modifier
			.size(Size.target)
			.clip(CircleShape)
			.clickable(role = Role.Button, onClick = onClick)
			.semantics { contentDescription = SettingsLabel },
		contentAlignment = Alignment.Center,
	) {
		SettingsAvatar(profile)
	}
}

@Composable
private fun SettingsAvatar(profile: PlexHomeUser?) {
	Avatar(profile, size = Space.s7, modifier = Modifier.clearAndSetSemantics { })
}

@Composable
private fun SidebarBrand(expanded: Boolean, onToggle: () -> Unit, labelAlpha: () -> Float) {
	// Counts the toggles after the first composition for the mark, which must not play on a restored sidebar.
	var toggles by remember { mutableIntStateOf(0) }
	var armed by remember { mutableStateOf(false) }
	LaunchedEffect(expanded) {
		if (armed) {
			toggles++
			// The page beside the sidebar changes width on every frame of the animation; a report should show when.
			Diag.info(DiagCategory.Ui, "sidebar.toggle", "expanded" to expanded)
		} else {
			armed = true
		}
	}

	Row(
		Modifier
			.testTag(SidebarTags.Brand)
			.fillMaxWidth()
			.height(Size.target)
			.clip(MaterialTheme.shapes.large)
			.clickable(
				onClickLabel = if (expanded) "Collapse sidebar" else "Expand sidebar",
				role = Role.Button,
				onClick = onToggle,
			)
			.semantics { contentDescription = BrandName },
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(Size.target), contentAlignment = Alignment.Center) {
			MarqueeMark(MarkSize, toggles = toggles, expanding = expanded)
		}
		Text(
			BrandName,
			style = MaterialTheme.typography.headlineSmall,
			color = lucent.ink,
			maxLines = 1,
			softWrap = false,
			overflow = TextOverflow.Clip,
			modifier = Modifier
				.padding(start = Space.s1)
				.clearAndSetSemantics { }
				.then(labelFade(labelAlpha)),
		)
	}
}

@Composable
private fun SidebarItem(
	key: String,
	label: String,
	selected: Boolean,
	labelAlpha: () -> Float,
	onSelect: (String) -> Unit,
	role: Role = Role.Tab,
	icon: @Composable (tint: Color) -> Unit,
) {
	val theme = lucent
	val container = MaterialTheme.colorScheme.secondaryContainer
	// The pill fades in place; it is read in draw, so fading it never recomposes the row.
	val pill = animateColorAsState(
		targetValue = if (selected) container else container.copy(alpha = 0f),
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "sidebar-pill",
	)
	val tint = if (selected) theme.ink else theme.ink2

	Row(
		Modifier
			.testTag(SidebarTags.item(key))
			.fillMaxWidth()
			.height(Size.target)
			.clip(MaterialTheme.shapes.large)
			.drawBehind { drawRect(pill.value) }
			.then(
				if (role == Role.Tab) Modifier.selectable(selected = selected, role = role) { onSelect(key) }
				else Modifier.clickable(role = role) { onSelect(key) },
			)
			// The label is mostly invisible in the rail, so the row carries the name itself.
			.semantics { contentDescription = label },
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(Size.target), contentAlignment = Alignment.Center) {
			icon(tint)
		}
		Text(
			label,
			style = MaterialTheme.typography.labelLarge,
			color = tint,
			maxLines = 1,
			softWrap = false,
			overflow = TextOverflow.Clip,
			modifier = Modifier
				.padding(start = Space.s1)
				.clearAndSetSemantics { }
				.then(labelFade(labelAlpha)),
		)
	}
}

/** The label's fade and slide, applied in the draw layer so the animation never recomposes. */
@Composable
private fun labelFade(alpha: () -> Float): Modifier {
	// A graphics layer cannot see the layout direction; labels arrive from the start edge in either.
	val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
	return Modifier.graphicsLayer {
		val fade = alpha()
		this.alpha = fade
		translationX = side * (1f - fade) * LabelSlide.toPx()
	}
}

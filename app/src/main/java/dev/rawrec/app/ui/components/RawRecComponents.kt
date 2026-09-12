@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.rawrec.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object RawRecUi {
    val SectionSpacing = 12.dp
    val InnerSpacing = 8.dp

    /**
     * Icon-only rotation: chrome layout stays pinned to its portrait
     * positions and only the GLYPHS rotate to read upright as the device
     * turns. Provided by MainActivity from the DeviceOrientationTracker.
     */
    val LocalIconRotation = androidx.compose.runtime.compositionLocalOf { 0f }
}

/** Rotates an icon glyph to the current device orientation (spring-animated). */
@Composable
fun Modifier.rotateIcon(): Modifier {
    val target by animateFloatAsState(
        targetValue = RawRecUi.LocalIconRotation.current,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "icon_rotation"
    )
    return graphicsLayer { rotationZ = target }
}

/**
 * Rotates a whole content block (text + glyphs together) to read upright in
 * the current device orientation — WITHOUT clipping.
 *
 * Plain graphicsLayer rotation reuses the portrait measurement, so a wide
 * panel rotated 90° pokes out of its slot and gets clipped by the parent
 * Surface (the "unusable landscape panel" bug). The fix is element-scale
 * RotatedOverlay geometry: when rotated (past 45°), the content is MEASURED
 * with swapped constraints (so it lays out landscape-shaped), then rotated
 * about its center — the rotated bounding box then exactly equals the
 * portrait slot. Touch input follows the transform (Compose hit-testing
 * respects graphicsLayer), so sliders/chips stay tappable.
 *
 * [contentCap] bounds the long dimension in landscape so tall panels don't
 * eat the viewfinder.
 */
@Composable
fun RotatedContent(
    modifier: Modifier = Modifier,
    contentCap: Dp = 520.dp,
    content: @Composable () -> Unit
) {
    val target by animateFloatAsState(
        targetValue = RawRecUi.LocalIconRotation.current,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "content_rotation"
    )
    Box(
        modifier
            .layout { measurable, constraints ->
                val swapped = kotlin.math.abs(target) > 45f
                if (swapped) {
                    // Measure landscape-shaped: child's max width = slot's
                    // available height (capped), child's max height = slot's width.
                    val maxW = kotlin.math.min(constraints.maxHeight, contentCap.roundToPx())
                    val maxH = constraints.maxWidth
                    val placeable = measurable.measure(
                        Constraints(maxWidth = maxW, maxHeight = maxH)
                    )
                    // Report the swapped size back as the slot: after rotating
                    // the child about its center, it exactly fills this
                    // portrait-shaped box.
                    val w = kotlin.math.min(placeable.height, constraints.maxWidth)
                    val h = kotlin.math.min(placeable.width, constraints.maxHeight)
                    layout(w, h) {
                        placeable.place(
                            ((w - placeable.width) / 2).coerceAtLeast(0),
                            ((h - placeable.height) / 2).coerceAtLeast(0)
                        )
                    }
                } else {
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                }
            }
    ) {
        Box(Modifier.graphicsLayer { rotationZ = target }) { content() }
    }
}

private val Bouncy = spring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
private val Soft = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

@Composable
fun SectionCard(
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    Surface(
        // Spec card mapping: medium (12dp), not large.
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring())
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(RawRecUi.InnerSpacing)
        ) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

@Composable
fun SettingRow(
    icon: ImageVector? = null,
    label: String,
    supporting: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = enabled && onClick != null,
        shape = MaterialTheme.shapes.small,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled
                )
            } else if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
fun StatChip(
    label: String,
    value: String,
    alert: Boolean = false
) {
    val container by animateColorAsState(
        targetValue = if (alert) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "stat_color"
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        // Tonal pairing: onX text on X container (was error-on-container).
        contentColor = if (alert) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = dev.rawrec.app.ui.theme.TelemetryStyle,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
            )
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun IconToggle(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) {
        pop.snapTo(0.8f)
        pop.animateTo(1f, Bouncy)
    }
    // Tonal elevation (primaryContainer fill) — no shadow; MD3 uses surface
    // color, not elevation shadows, for resting components.
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(48.dp).scale(pop.value)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(16.dp).rotateIcon()
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.rotateIcon()
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp).rotateIcon()
                )
            }
        }
    }
}

@Composable
fun ChoiceChipRow(
    options: List<String>,
    selected: (String) -> Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { opt ->
            val isSelected = selected(opt)
            val pop = remember { Animatable(1f) }
            LaunchedEffect(isSelected) {
                if (isSelected) {
                    pop.snapTo(0.9f)
                    pop.animateTo(1f, Bouncy)
                }
            }
            val container by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "chip_color"
            )
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(opt)
                },
                shape = MaterialTheme.shapes.small,
                color = container,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.scale(pop.value)
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        opt,
                        style = if (isSelected) dev.rawrec.app.ui.theme.TelemetryEmphasizedStyle
                        else dev.rawrec.app.ui.theme.TelemetryStyle,
                        modifier = Modifier.rotateIcon()
                    )
                }
            }
        }
    }
}

@Composable
fun ControlPill(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = Bouncy,
        label = "pill_press"
    )
    // MD3 buttons are full-rounded; the pill morphs 12dp (rest) -> full
    // (active) — spec shape morphing, expressed as corner percent.
    val cornerPercent by animateFloatAsState(
        targetValue = if (active) 50f else 25f,
        animationSpec = Bouncy,
        label = "pill_corner"
    )
    val container by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.secondaryContainer
            highlight -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pill_color"
    )
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        interactionSource = interaction,
        shape = RoundedCornerShape(cornerPercent),
        color = container,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .scale(pressScale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.rotateIcon(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    value,
                    style = dev.rawrec.app.ui.theme.TelemetryStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun Hudpill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = MaterialTheme.shapes.medium,
        // Unmodified role — tonal pairing holds; no alpha copy.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        // No rotation here: the HUD is a wide-short strip, and RotatedContent
        // would report its rotated bounding box (36dp x ~200dp) as the slot,
        // turning the pill into a vertical bar in landscape. HUD text stays
        // window-horizontal; the status dot is a circle (invariant).
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
fun StaggeredAppear(
    index: Int,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val offset = remember { Animatable(24f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * 40L).coerceAtMost(240L))
        kotlinx.coroutines.coroutineScope {
            launch { alpha.animateTo(1f, Soft) }
            launch { offset.animateTo(0f, Soft) }
        }
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = offset.value
        }
    ) {
        content()
    }
}

@Composable
fun RecordButtonExpressive(
    recording: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rec_press"
    )
    val morph by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "record_morph"
    )
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "rec_scale"
    )
    val scale = pressScale * (if (recording) pulse else 1f)

    Surface(
        onClick = onToggle,
        interactionSource = interaction,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = if (recording) 10.dp else 4.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .size(56.dp)
            .scale(scale)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val shapeFraction = 1f - morph
            Box(
                Modifier
                    .size(24.dp)
                    .clip(
                        RoundedCornerShape(percent = (50 * shapeFraction + 20 * morph).toInt())
                    )
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

package dev.rawrec.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckValueStyle

/**
 * Material 3 Expressive cinema parameter tile.
 *
 * Features:
 * - Spring-physics shape morphing (14dp card at rest -> 22dp stadium pill when active).
 * - Category vector icon + uppercase micro-label + optional Auto/Manual badge.
 * - Tonal container elevation (surfaceContainerHigh -> secondaryContainer on manual -> primaryContainer when active).
 */
@Composable
fun CinemaParameterTile(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlight: Boolean = false,
    modeBadge: String? = null,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tileScale"
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (active) 22.dp else 14.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tileCorner"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primary
            highlight -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileBorder"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer
            highlight -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileBg"
    )

    val contentColor = when {
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        highlight -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        interactionSource = interaction,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(if (active) 1.5.dp else 1.dp, borderColor),
        modifier = modifier.scale(scale)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = when {
                                active -> MaterialTheme.colorScheme.primary
                                highlight -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(11.dp).rotateIcon()
                        )
                    }
                    Text(
                        text = label.uppercase(),
                        style = DeckLabelStyle.copy(letterSpacing = 0.6.sp),
                        color = when {
                            active -> MaterialTheme.colorScheme.primary
                            highlight -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
                if (modeBadge != null) {
                    Surface(
                        shape = CircleShape,
                        color = if (highlight) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = modeBadge,
                            style = DeckLabelStyle.copy(fontSize = 8.sp, letterSpacing = 0.4.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (highlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Text(
                text = value,
                style = DeckValueStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact right-side Material 3 Expressive dock housing a 2x3 matrix of parameter
 * tiles (FPS, Shutter, ISO, WB, Look, Res) and a sculpted master Record Trigger well.
 */
@Composable
fun CinemaParameterDock(
    fpsValue: String,
    shutterValue: String,
    isoValue: String,
    wbValue: String,
    lookValue: String,
    resValue: String,
    activeTile: String?,
    autoExposure: Boolean,
    autoWb: Boolean,
    recording: Boolean,
    onTileClick: (String) -> Unit,
    onRecordToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.width(234.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: FPS & Shutter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CinemaParameterTile(
                    label = "FPS",
                    value = fpsValue,
                    icon = RawRecIcons.Speed,
                    active = activeTile == "FPS",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("FPS") }

                CinemaParameterTile(
                    label = "Shutter",
                    value = shutterValue,
                    icon = RawRecIcons.Shutter,
                    active = activeTile == "SHUTTER",
                    highlight = !autoExposure,
                    modeBadge = if (autoExposure) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("SHUTTER") }
            }

            // Row 2: ISO & WB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CinemaParameterTile(
                    label = "ISO",
                    value = isoValue,
                    icon = RawRecIcons.Iso,
                    active = activeTile == "ISO",
                    highlight = !autoExposure,
                    modeBadge = if (autoExposure) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("ISO") }

                CinemaParameterTile(
                    label = "WB",
                    value = wbValue,
                    icon = RawRecIcons.WhiteBalance,
                    active = activeTile == "WB",
                    highlight = !autoWb,
                    modeBadge = if (autoWb) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("WB") }
            }

            // Row 3: Look / Tone & Resolution
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CinemaParameterTile(
                    label = "Look",
                    value = lookValue,
                    icon = RawRecIcons.Tune,
                    active = activeTile == "LOOK",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("LOOK") }

                CinemaParameterTile(
                    label = "Res",
                    value = resValue,
                    icon = RawRecIcons.Layers,
                    active = activeTile == "RES",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("RES") }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Master Cinema Record Trigger Well (M3 Expressive Tonal Dock)
            Surface(
                shape = CircleShape,
                color = if (recording) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (recording) "RECORDING" else "READY",
                            style = DeckLabelStyle,
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (recording) "Tap to stop" else "RAW Capture",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RecordButtonExpressive(
                        recording = recording,
                        onToggle = onRecordToggle
                    )
                }
            }
        }
    }
}

/**
 * Adaptive 2x3 parameter matrix grid for portrait/chassis-locked mode.
 * Spans the full width with 3 parameter tiles per row.
 */
@Composable
fun CinemaParameterGrid(
    fpsValue: String,
    shutterValue: String,
    isoValue: String,
    wbValue: String,
    lookValue: String,
    resValue: String,
    activeTile: String?,
    autoExposure: Boolean,
    autoWb: Boolean,
    onTileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: FPS, Shutter, ISO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CinemaParameterTile(
                    label = "FPS",
                    value = fpsValue,
                    icon = RawRecIcons.Speed,
                    active = activeTile == "FPS",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("FPS") }

                CinemaParameterTile(
                    label = "Shutter",
                    value = shutterValue,
                    icon = RawRecIcons.Shutter,
                    active = activeTile == "SHUTTER",
                    highlight = !autoExposure,
                    modeBadge = if (autoExposure) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("SHUTTER") }

                CinemaParameterTile(
                    label = "ISO",
                    value = isoValue,
                    icon = RawRecIcons.Iso,
                    active = activeTile == "ISO",
                    highlight = !autoExposure,
                    modeBadge = if (autoExposure) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("ISO") }
            }

            // Row 2: WB, Look, Res
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CinemaParameterTile(
                    label = "WB",
                    value = wbValue,
                    icon = RawRecIcons.WhiteBalance,
                    active = activeTile == "WB",
                    highlight = !autoWb,
                    modeBadge = if (autoWb) "A" else "M",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("WB") }

                CinemaParameterTile(
                    label = "Look",
                    value = lookValue,
                    icon = RawRecIcons.Tune,
                    active = activeTile == "LOOK",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("LOOK") }

                CinemaParameterTile(
                    label = "Res",
                    value = resValue,
                    icon = RawRecIcons.Layers,
                    active = activeTile == "RES",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("RES") }
            }
        }
    }
}

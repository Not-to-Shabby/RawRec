package dev.rawrec.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckValueStyle

/**
 * Cinema studio parameter matrix cell.
 *
 * High-density studio parameter tile with an uppercase micro-label above a bold
 * monospaced value readout and an active Electric Cyan indicator glow.
 */
@Composable
fun CinemaParameterTile(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primary
            highlight -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(150),
        label = "tileBorder"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
        },
        animationSpec = tween(150),
        label = "tileBg"
    )

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label.uppercase(),
                style = DeckLabelStyle,
                color = if (active || highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = DeckValueStyle,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/**
 * Compact right-side dock housing a grid of direct-access parameter tiles
 * (FPS, Shutter, ISO, WB, Look, Res) and the master Record Trigger.
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
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.width(220.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: FPS & Shutter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CinemaParameterTile(
                    label = "FPS",
                    value = fpsValue,
                    active = activeTile == "FPS",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("FPS") }

                CinemaParameterTile(
                    label = "Shutter",
                    value = shutterValue,
                    active = activeTile == "SHUTTER",
                    highlight = !autoExposure,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("SHUTTER") }
            }

            // Row 2: ISO & WB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CinemaParameterTile(
                    label = "ISO",
                    value = isoValue,
                    active = activeTile == "ISO",
                    highlight = !autoExposure,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("ISO") }

                CinemaParameterTile(
                    label = "WB",
                    value = wbValue,
                    active = activeTile == "WB",
                    highlight = !autoWb,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("WB") }
            }

            // Row 3: Look / Tone & Resolution
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CinemaParameterTile(
                    label = "Look",
                    value = lookValue,
                    active = activeTile == "LOOK",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("LOOK") }

                CinemaParameterTile(
                    label = "Res",
                    value = resValue,
                    active = activeTile == "RES",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("RES") }
            }

            Spacer(Modifier.height(4.dp))

            // Master Cinema Record Button (Anchored under right thumb)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                RecordButtonExpressive(
                    recording = recording,
                    onToggle = onRecordToggle
                )
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
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: FPS, Shutter, ISO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CinemaParameterTile(
                    label = "FPS",
                    value = fpsValue,
                    active = activeTile == "FPS",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("FPS") }

                CinemaParameterTile(
                    label = "Shutter",
                    value = shutterValue,
                    active = activeTile == "SHUTTER",
                    highlight = !autoExposure,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("SHUTTER") }

                CinemaParameterTile(
                    label = "ISO",
                    value = isoValue,
                    active = activeTile == "ISO",
                    highlight = !autoExposure,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("ISO") }
            }

            // Row 2: WB, Look, Res
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CinemaParameterTile(
                    label = "WB",
                    value = wbValue,
                    active = activeTile == "WB",
                    highlight = !autoWb,
                    modifier = Modifier.weight(1f)
                ) { onTileClick("WB") }

                CinemaParameterTile(
                    label = "Look",
                    value = lookValue,
                    active = activeTile == "LOOK",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("LOOK") }

                CinemaParameterTile(
                    label = "Res",
                    value = resValue,
                    active = activeTile == "RES",
                    modifier = Modifier.weight(1f)
                ) { onTileClick("RES") }
            }
        }
    }
}

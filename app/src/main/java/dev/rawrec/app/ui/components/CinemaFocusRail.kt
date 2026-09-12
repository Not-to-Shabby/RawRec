package dev.rawrec.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckValueStyle
import kotlinx.coroutines.launch

/**
 * Cinema studio horizontal focus rail with A-B rack focus markers.
 *
 * Allows smooth manual focus pulling from infinity (0.0D) to macro (10.0D)
 * with dedicated A and B focus memory points for repeatable cinematic focus pulls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CinemaFocusRail(
    currentDiopters: Float,
    autoFocus: Boolean,
    onFocusChanged: (diopters: Float, autoFocus: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var pointA by remember { mutableStateOf<Float?>(null) }
    var pointB by remember { mutableStateOf<Float?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // AF / MF Button
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (autoFocus) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .width(48.dp)
                    .height(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = { onFocusChanged(currentDiopters, !autoFocus) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (autoFocus) "AF" else "MF",
                        style = DeckLabelStyle,
                        color = if (autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Rack Focus Marker A Button
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (pointA != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (pointA != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .width(36.dp)
                    .height(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = {
                                pointA?.let { target ->
                                    coroutineScope.launch {
                                        val anim = Animatable(currentDiopters)
                                        anim.animateTo(target, tween(durationMillis = 1200)) {
                                            onFocusChanged(value, false)
                                        }
                                    }
                                } ?: run { pointA = currentDiopters }
                            },
                            onLongClick = { pointA = currentDiopters }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "A",
                        style = DeckLabelStyle,
                        color = if (pointA != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Slider track
            Box(Modifier.weight(1f)) {
                Slider(
                    value = currentDiopters.coerceIn(0f, 10f),
                    onValueChange = { onFocusChanged(it, false) },
                    valueRange = 0f..10f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Rack Focus Marker B Button
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (pointB != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (pointB != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .width(36.dp)
                    .height(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = {
                                pointB?.let { target ->
                                    coroutineScope.launch {
                                        val anim = Animatable(currentDiopters)
                                        anim.animateTo(target, tween(durationMillis = 1200)) {
                                            onFocusChanged(value, false)
                                        }
                                    }
                                } ?: run { pointB = currentDiopters }
                            },
                            onLongClick = { pointB = currentDiopters }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "B",
                        style = DeckLabelStyle,
                        color = if (pointB != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Digital Focus Distance Readout
            Text(
                text = when {
                    autoFocus -> "AUTO"
                    currentDiopters < 0.1f -> "∞"
                    else -> "%.1fm".format(1.0f / currentDiopters)
                },
                style = DeckValueStyle,
                color = if (!autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp)
            )
        }
    }
}

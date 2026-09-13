package dev.rawrec.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.control.FocusCalculator
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckValueStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Cinema studio horizontal focus rail with visual A-B rack focus markers.
 *
 * Provides:
 * - Smooth manual focus pulling from infinity (0.0D) to macro (10.0D).
 * - Persistent Mark A (Cyan) and Mark B (Amber) memory points with visual witness pins.
 * - One-tap [A ➔ B] and [B ➔ A] rack focus puller with 30 FPS HAL pacing to prevent motor stutter.
 * - Adjustable pull speeds (0.5s Fast Snap, 1.2s Normal Cinema, 2.5s Slow Dramatic).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CinemaFocusRail(
    currentDiopters: Float,
    autoFocus: Boolean,
    focusPointA: Float? = null,
    focusPointB: Float? = null,
    rackDurationMs: Long = 1200L,
    onFocusChanged: (diopters: Float, autoFocus: Boolean) -> Unit,
    onSetPointA: (Float?) -> Unit = {},
    onSetPointB: (Float?) -> Unit = {},
    onSetRackDuration: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isRacking by remember { mutableStateOf(false) }
    var rackingJob by remember { mutableStateOf<Job?>(null) }
    val animatedDiopters = remember { Animatable(currentDiopters) }

    fun triggerRack(target: Float) {
        rackingJob?.cancel()
        isRacking = true
        rackingJob = coroutineScope.launch {
            try {
                var lastDispatchMs = 0L
                animatedDiopters.snapTo(currentDiopters)
                animatedDiopters.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = rackDurationMs.toInt(),
                        easing = FastOutSlowInEasing
                    )
                ) {
                    val now = SystemClock.uptimeMillis()
                    // Paced to 30 FPS (~33ms) to avoid flooding Qualcomm Camera2 HAL
                    if (now - lastDispatchMs >= 33L || value == target) {
                        lastDispatchMs = now
                        onFocusChanged(value, false)
                    }
                }
                onFocusChanged(target, false)
            } finally {
                isRacking = false
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Main Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // AF / MF Button
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (autoFocus) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, if (autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .width(44.dp)
                        .height(30.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onFocusChanged(currentDiopters, !autoFocus) },
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

                // Mark A Button (Electric Cyan)
                val isAtA = focusPointA != null && kotlin.math.abs(currentDiopters - focusPointA) < 0.08f
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        isAtA -> Color(0xFF00E5FF).copy(alpha = 0.35f)
                        focusPointA != null -> Color(0xFF00E5FF).copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(1.dp, if (focusPointA != null) Color(0xFF00E5FF) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .height(30.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    if (focusPointA != null) {
                                        triggerRack(focusPointA)
                                    } else {
                                        onSetPointA(currentDiopters)
                                    }
                                },
                                onLongClick = { onSetPointA(currentDiopters) }
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (focusPointA != null) "A ${FocusCalculator.formatFocusDistance(focusPointA)}" else "Set A",
                            style = DeckLabelStyle,
                            color = if (focusPointA != null) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Slider Track with Colored Witness Pins
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Slider(
                        value = currentDiopters.coerceIn(0f, 10f),
                        onValueChange = {
                            if (!isRacking) {
                                onFocusChanged(it, false)
                            }
                        },
                        valueRange = 0f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = when {
                                isRacking -> MaterialTheme.colorScheme.error
                                isAtA -> Color(0xFF00E5FF)
                                focusPointB != null && kotlin.math.abs(currentDiopters - focusPointB) < 0.08f -> Color(0xFFFFB300)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Overlay Witness Pins for A & B
                    Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                        val trackPadding = 12.dp.toPx()
                        val usableW = size.width - trackPadding * 2

                        focusPointA?.let { pA ->
                            val x = trackPadding + (pA.coerceIn(0f, 10f) / 10f) * usableW
                            drawLine(
                                color = Color(0xFF00E5FF),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f
                            )
                            drawCircle(Color(0xFF00E5FF), radius = 3.5f, center = Offset(x, 0f))
                        }

                        focusPointB?.let { pB ->
                            val x = trackPadding + (pB.coerceIn(0f, 10f) / 10f) * usableW
                            drawLine(
                                color = Color(0xFFFFB300),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f
                            )
                            drawCircle(Color(0xFFFFB300), radius = 3.5f, center = Offset(x, size.height))
                        }
                    }
                }

                // Mark B Button (Amber)
                val isAtB = focusPointB != null && kotlin.math.abs(currentDiopters - focusPointB) < 0.08f
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        isAtB -> Color(0xFFFFB300).copy(alpha = 0.35f)
                        focusPointB != null -> Color(0xFFFFB300).copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(1.dp, if (focusPointB != null) Color(0xFFFFB300) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .height(30.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    if (focusPointB != null) {
                                        triggerRack(focusPointB)
                                    } else {
                                        onSetPointB(currentDiopters)
                                    }
                                },
                                onLongClick = { onSetPointB(currentDiopters) }
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (focusPointB != null) "B ${FocusCalculator.formatFocusDistance(focusPointB)}" else "Set B",
                            style = DeckLabelStyle,
                            color = if (focusPointB != null) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // One-Tap Rack Focus Trigger (A ➔ B or B ➔ A)
                if (focusPointA != null && focusPointB != null) {
                    val targetIsB = !isAtB
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (isRacking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, if (isRacking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    triggerRack(if (targetIsB) focusPointB else focusPointA)
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isRacking) "PULLING" else if (targetIsB) "RACK A➔B" else "RACK B➔A",
                                style = DeckLabelStyle,
                                color = if (isRacking) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Rack Speed Selector Chip
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clickable {
                                val next = when (rackDurationMs) {
                                    500L -> 1200L
                                    1200L -> 2500L
                                    else -> 500L
                                }
                                onSetRackDuration(next)
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (rackDurationMs) {
                                500L -> "⚡ 0.5s"
                                2500L -> "🎬 2.5s"
                                else -> "⏱ 1.2s"
                            },
                            style = DeckLabelStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Digital Focus Distance Readout
                Text(
                    text = when {
                        autoFocus -> "AUTO"
                        currentDiopters < 0.05f -> "∞"
                        else -> FocusCalculator.formatFocusDistance(currentDiopters)
                    },
                    style = DeckValueStyle,
                    color = if (!autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp)
                )
            }
        }
    }
}

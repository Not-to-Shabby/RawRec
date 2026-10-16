package dev.rawrec.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    isFixedFocus: Boolean = false,
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

    val markAColor = MaterialTheme.colorScheme.primary
    val markBColor = MaterialTheme.colorScheme.tertiary
    val tickColor = MaterialTheme.colorScheme.outlineVariant

    val afCorner by animateDpAsState(
        targetValue = if (autoFocus) 18.dp else 10.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "afCorner"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Main Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // AF / MF Expressive Toggle Button
                Surface(
                    shape = RoundedCornerShape(afCorner),
                    color = when {
                        isFixedFocus -> MaterialTheme.colorScheme.surfaceContainer
                        autoFocus -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        if (!isFixedFocus && autoFocus) 1.5.dp else 1.dp,
                        if (!isFixedFocus && autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !isFixedFocus) { onFocusChanged(currentDiopters, !autoFocus) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = RawRecIcons.FocusTarget,
                            contentDescription = null,
                            tint = if (!isFixedFocus && autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp).rotateIcon()
                        )
                        Text(
                            text = if (isFixedFocus) "FIX" else if (autoFocus) "AF" else "MF",
                            style = DeckLabelStyle,
                            color = if (!isFixedFocus && autoFocus) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Mark A Button (Primary Role Pill)
                val isAtA = focusPointA != null && kotlin.math.abs(currentDiopters - focusPointA) < 0.08f
                Surface(
                    shape = CircleShape,
                    color = when {
                        isFixedFocus -> MaterialTheme.colorScheme.surfaceContainer
                        isAtA -> MaterialTheme.colorScheme.primaryContainer
                        focusPointA != null -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(1.dp, if (!isFixedFocus && focusPointA != null) markAColor else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                enabled = !isFixedFocus,
                                onClick = {
                                    if (focusPointA != null) {
                                        triggerRack(focusPointA)
                                    } else {
                                        onSetPointA(currentDiopters)
                                    }
                                },
                                onLongClick = { onSetPointA(currentDiopters) }
                            )
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (!isFixedFocus && focusPointA != null) markAColor else MaterialTheme.colorScheme.outline)
                        )
                        Text(
                            text = if (isFixedFocus) "A —" else if (focusPointA != null) "A ${FocusCalculator.formatFocusDistance(focusPointA)}" else "Set A",
                            style = DeckLabelStyle,
                            color = if (!isFixedFocus && focusPointA != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Slider Track with Follow-Focus Scale Ticks & Colored Witness Pins
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Slider(
                        value = currentDiopters.coerceIn(0f, 10f),
                        enabled = !isFixedFocus,
                        onValueChange = {
                            if (!isRacking) {
                                onFocusChanged(it, false)
                            }
                        },
                        valueRange = 0f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = when {
                                isFixedFocus -> MaterialTheme.colorScheme.outline
                                isRacking -> MaterialTheme.colorScheme.error
                                isAtA -> markAColor
                                focusPointB != null && kotlin.math.abs(currentDiopters - focusPointB) < 0.08f -> markBColor
                                else -> MaterialTheme.colorScheme.primary
                            },
                            activeTrackColor = if (isFixedFocus) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Overlay Follow-Focus Scale Ticks + Witness Pins for A & B
                    if (!isFixedFocus) {
                        Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
                            val trackPadding = 12.dp.toPx()
                            val usableW = size.width - trackPadding * 2

                            // Subtle follow-focus barrel scale ticks
                            for (i in 0..20) {
                                val tx = trackPadding + (i / 20f) * usableW
                                val tickH = if (i % 5 == 0) 6f else 3f
                                drawLine(
                                    color = tickColor,
                                    start = Offset(tx, size.height - tickH),
                                    end = Offset(tx, size.height),
                                    strokeWidth = 1.5f
                                )
                            }

                            focusPointA?.let { pA ->
                                val x = trackPadding + (pA.coerceIn(0f, 10f) / 10f) * usableW
                                drawLine(
                                    color = markAColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 3f
                                )
                                drawCircle(markAColor, radius = 4f, center = Offset(x, 0f))
                            }

                            focusPointB?.let { pB ->
                                val x = trackPadding + (pB.coerceIn(0f, 10f) / 10f) * usableW
                                drawLine(
                                    color = markBColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 3f
                                )
                                drawCircle(markBColor, radius = 4f, center = Offset(x, size.height))
                            }
                        }
                    }
                }

                // Mark B Button (Tertiary Role Pill)
                val isAtB = focusPointB != null && kotlin.math.abs(currentDiopters - focusPointB) < 0.08f
                Surface(
                    shape = CircleShape,
                    color = when {
                        isFixedFocus -> MaterialTheme.colorScheme.surfaceContainer
                        isAtB -> MaterialTheme.colorScheme.tertiaryContainer
                        focusPointB != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(1.dp, if (!isFixedFocus && focusPointB != null) markBColor else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                enabled = !isFixedFocus,
                                onClick = {
                                    if (focusPointB != null) {
                                        triggerRack(focusPointB)
                                    } else {
                                        onSetPointB(currentDiopters)
                                    }
                                },
                                onLongClick = { onSetPointB(currentDiopters) }
                            )
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (!isFixedFocus && focusPointB != null) markBColor else MaterialTheme.colorScheme.outline)
                        )
                        Text(
                            text = if (isFixedFocus) "B —" else if (focusPointB != null) "B ${FocusCalculator.formatFocusDistance(focusPointB)}" else "Set B",
                            style = DeckLabelStyle,
                            color = if (!isFixedFocus && focusPointB != null) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // One-Tap Rack Focus Trigger (A ➔ B or B ➔ A)
                if (!isFixedFocus && focusPointA != null && focusPointB != null) {
                    val targetIsB = !isAtB
                    Surface(
                        shape = CircleShape,
                        color = if (isRacking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, if (isRacking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    triggerRack(if (targetIsB) focusPointB else focusPointA)
                                }
                                .padding(horizontal = 10.dp),
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
                if (!isFixedFocus) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.height(34.dp)
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
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (rackDurationMs) {
                                    500L -> "0.5s FAST"
                                    2500L -> "2.5s SLOW"
                                    else -> "1.2s CINE"
                                },
                                style = DeckLabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Recessed Digital Focus Distance Readout Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (!isFixedFocus && !autoFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 56.dp)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DIST",
                            style = DeckLabelStyle.copy(fontSize = 8.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                isFixedFocus -> "FIXED"
                                autoFocus -> "AUTO"
                                currentDiopters < 0.05f -> "∞"
                                else -> FocusCalculator.formatFocusDistance(currentDiopters)
                            },
                            style = DeckValueStyle.copy(fontSize = 13.sp),
                            color = if (!isFixedFocus && !autoFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

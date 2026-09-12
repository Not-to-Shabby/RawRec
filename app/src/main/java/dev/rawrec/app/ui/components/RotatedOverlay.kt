package dev.rawrec.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's own rotation layer (activity is locked portrait).
 *
 * Centers a dimension-swapped box and rotates it with a spring, so content
 * laid out "portrait" appears correctly when the device is held landscape.
 * Pivot = screen center; the swapped box exactly covers the screen at 90/270.
 *
 * Touch input is transformed inversely automatically (Compose hit-testing
 * respects graphicsLayer), so controls remain tappable after rotation.
 *
 * [animate] false = snap (stiff spring, no bounce) — for viewfinder overlays
 * that must track the TextureView transform exactly, not lag behind it.
 */
@Composable
fun RotatedOverlay(
    uiRotation: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = (uiRotation % 360).toFloat(),
        animationSpec = if (animate) spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "rotated_overlay"
    )
    val swap = uiRotation == 90 || uiRotation == 270
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .let { m ->
                    if (swap) m.size(width = maxHeight, height = maxWidth)
                    else m.fillMaxSize()
                }
                .graphicsLayer { rotationZ = rotation },
            content = content
        )
    }
}

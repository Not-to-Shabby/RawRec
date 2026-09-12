package dev.rawrec.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Self-rotation container: rotates its content by [rotation] degrees (0/90/180/270)
 * inside the always-portrait window, with spring animation.
 *
 * The activity is locked portrait (screenOrientation="portrait"); the app's
 * accelerometer-driven rotation engine reports [rotation] and this container
 * does the visual rotation Android would normally do — but WITHOUT destroying
 * the viewfinder, which lives outside this container.
 *
 * Mechanics:
 * - 90/270: the inner box takes SWAPPED dimensions (requiredSize — escapes the
 *   portrait constraints, which would otherwise clamp the child to a square)
 *   and is centered, so after rotation it exactly covers the screen.
 * - 0/180: the inner box fills the window and pivots around its center.
 * - Touch input is transformed through graphicsLayer, so controls stay
 *   clickable at their visual positions.
 */
@Composable
fun RotatingContainer(
    rotation: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val swap = rotation == 90 || rotation == 270
    val angle by animateFloatAsState(
        targetValue = (rotation % 360).toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ui_rotation"
    )
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .let { m ->
                    if (swap) m.requiredSize(width = maxHeight, height = maxWidth)
                    else m.fillMaxSize()
                }
                .graphicsLayer { rotationZ = angle }
        ) {
            content()
        }
    }
}

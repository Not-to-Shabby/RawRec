package dev.rawrec.app.ui.gl

import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rawrec.tool.CubeLut

/**
 * Hardware-accelerated OpenGL ES 3.0 Viewfinder Composable.
 *
 * Runs an embedded GLSurfaceView with live 3D LUT grading, focus peaking,
 * false color, and zebras computed directly on the Adreno GPU.
 */
@Composable
fun GlViewfinder(
    modifier: Modifier = Modifier,
    bufferWidth: Int = 960,
    bufferHeight: Int = 720,
    rawAspect: Double = 4.0 / 3.0,
    activeLut: CubeLut? = null,
    peakingActive: Boolean = false,
    falseColorActive: Boolean = false,
    zebrasActive: Boolean = false,
    onPreviewSurfaceAvailable: (Surface) -> Unit,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val renderer = remember { GlViewfinderRenderer(bufferWidth, bufferHeight, onPreviewSurfaceAvailable) }

    LaunchedEffect(bufferWidth, bufferHeight) {
        renderer.updateBufferSize(bufferWidth, bufferHeight)
    }

    LaunchedEffect(activeLut) {
        renderer.setLut(activeLut)
    }

    LaunchedEffect(peakingActive, falseColorActive, zebrasActive) {
        renderer.peakingActive = peakingActive
        renderer.falseColorActive = falseColorActive
        renderer.zebrasActive = zebrasActive
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value
        val aspect = rawAspect.toFloat().coerceAtLeast(0.5f)

        // Physical aspect sizing to prevent anisotropic squashing
        var targetW = maxW
        var targetH = maxW / aspect
        if (targetH > maxH) {
            targetH = maxH
            targetW = maxH * aspect
        }

        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setZOrderMediaOverlay(true)
                    holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                    renderer.attachView(this)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay layer (framing outline, lookaround scrim, gridlines)
        overlay()
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.release()
        }
    }
}

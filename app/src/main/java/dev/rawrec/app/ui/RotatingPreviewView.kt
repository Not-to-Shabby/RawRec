package dev.rawrec.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Rotation-aware camera preview (Camera2Basic configureTransform recipe).
 *
 * - Producer buffer is 4:3; dims swap with window rotation so the HAL output
 *   aspect matches the window after the rotation transform.
 * - TextureView transform rotates the buffer by
 *   (sensorOrientation - displayRotation) and scales to COVER the view:
 *   upright, aspect-preserving, no stretch.
 * - Rotation change recomposes; update{} re-applies the transform.
 */
@Composable
fun RotatingPreviewView(
    onPreviewSurfaceAvailable: ((Surface?) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayRotation = remember(context) {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
    }

    AndroidView(
        factory = { ctx -> TextureView(ctx) },
        modifier = modifier,
        update = { textureView ->
            configurePreview(textureView, displayRotation, onPreviewSurfaceAvailable)
        }
    )
}

private fun configurePreview(
    textureView: TextureView,
    rotation: Int,
    onSurfaceAvailable: ((Surface?) -> Unit)?
) {
    if (textureView.surfaceTextureListener == null) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val dims = bufferDimsFor(rotation)
                st.setDefaultBufferSize(dims.width, dims.height)
                applyTransform(textureView, rotation)
                onSurfaceAvailable?.invoke(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyTransform(textureView, rotation)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                onSurfaceAvailable?.invoke(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    textureView.surfaceTexture?.let { st ->
        val dims = bufferDimsFor(rotation)
        st.setDefaultBufferSize(dims.width, dims.height)
        applyTransform(textureView, rotation)
    }
}

// Back camera of this device family reports SENSOR_ORIENTATION = 90.
private const val SENSOR_ORIENTATION = 90

private fun bufferDimsFor(rotation: Int): Size =
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        Size(1440, 1920)
    } else {
        Size(1920, 1440)
    }

private fun applyTransform(textureView: TextureView, rotation: Int) {
    val viewW = textureView.width.coerceAtLeast(1)
    val viewH = textureView.height.coerceAtLeast(1)

    // Effective content dims AFTER the rotation transform is applied
    val rotDegrees = (SENSOR_ORIENTATION - rotation + 360) % 360
    val swap = rotDegrees == 90 || rotDegrees == 270
    val bufW = if (swap) 1440 else 1920
    val bufH = if (swap) 1920 else 1440

    // Scale to cover: fit the rotated content box to the view, uniform scale
    val scale = maxOf(viewW.toFloat() / bufW, viewH.toFloat() / bufH)

    val matrix = Matrix()
    when (rotDegrees) {
        0 -> { /* identity */ }
        90 -> {
            matrix.setScale(0f, -scale)   // (x,y) -> (0, -y)
            matrix.postTranslate(viewH * scale, 0f)
        }
        180 -> {
            matrix.setScale(-scale, -scale)
            matrix.postTranslate(viewW * scale, viewH * scale)
        }
        270 -> {
            matrix.setScale(0f, scale)    // (x,y) -> (y, 0) scaled
            matrix.postTranslate(0f, viewW * scale)
        }
        else -> matrix.setScale(scale, scale)
    }
    if (rotDegrees == 0) matrix.setScale(scale, scale)

    textureView.setTransform(matrix)
}

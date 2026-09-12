package dev.rawrec.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val STROKE = 2f
private val Stroke = SolidColor(Color.Black)
private val Fill = SolidColor(Color.Black)

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    val k = 0.5523f * r
    moveTo(cx + r, cy)
    curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    close()
}

private fun builder(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

object RawRecIcons {

    val Camera: ImageVector by lazy {
        builder("Camera") {
            path(
                fill = null, fillAlpha = 1f,
                stroke = Stroke, strokeAlpha = 1f, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 7.5f)
                lineTo(21f, 7.5f)
                lineTo(21f, 19.5f)
                lineTo(3f, 19.5f)
                close()
                moveTo(8.5f, 7.5f)
                lineTo(10f, 4.5f)
                lineTo(14f, 4.5f)
                lineTo(15.5f, 7.5f)
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) { circle(12f, 13.2f, 3.2f) }
        }
    }

    val Tune: ImageVector by lazy {
        builder("Tune") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6f); lineTo(13f, 6f); moveTo(19f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(7f, 12f); moveTo(13f, 12f); lineTo(20f, 12f)
                moveTo(4f, 18f); lineTo(13f, 18f); moveTo(19f, 18f); lineTo(20f, 18f)
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                circle(15.6f, 6f, 2.1f); circle(9.6f, 12f, 2.1f); circle(15.6f, 18f, 2.1f)
            }
        }
    }

    val Probe: ImageVector by lazy {
        builder("Probe") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f); lineTo(7f, 12f); lineTo(10f, 6f)
                lineTo(14f, 18f); lineTo(17f, 12f); lineTo(22f, 12f)
            }
        }
    }

    val Histogram: ImageVector by lazy {
        builder("Histogram") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 20f); lineTo(20f, 20f)
                moveTo(6.5f, 20f); lineTo(6.5f, 13f)
                moveTo(10.5f, 20f); lineTo(10.5f, 8f)
                moveTo(14.5f, 20f); lineTo(14.5f, 11f)
                moveTo(18.5f, 20f); lineTo(18.5f, 5f)
            }
        }
    }

    val Peaks: ImageVector by lazy {
        builder("Peaks") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 19f); lineTo(9f, 7f); lineTo(13f, 14f)
                lineTo(16f, 9f); lineTo(21f, 19f)
            }
        }
    }

    val Zebra: ImageVector by lazy {
        builder("Zebra") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 19f); lineTo(11f, 5f)
                moveTo(10f, 19f); lineTo(16f, 5f)
                moveTo(15f, 19f); lineTo(21f, 5f)
            }
        }
    }

    val FalseColor: ImageVector by lazy {
        builder("FalseColor") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.5f, 4.5f); lineTo(11.2f, 4.5f)
                lineTo(11.2f, 11.2f); lineTo(4.5f, 11.2f); close()
                moveTo(12.8f, 4.5f); lineTo(19.5f, 4.5f)
                lineTo(19.5f, 11.2f); lineTo(12.8f, 11.2f); close()
                moveTo(4.5f, 12.8f); lineTo(11.2f, 12.8f)
                lineTo(11.2f, 19.5f); lineTo(4.5f, 19.5f); close()
                moveTo(12.8f, 12.8f); lineTo(19.5f, 12.8f)
                lineTo(19.5f, 19.5f); lineTo(12.8f, 19.5f); close()
            }
        }
    }

    val Aspect: ImageVector by lazy {
        builder("Aspect") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
                moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
                moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
                moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            }
        }
    }

    val Shutter: ImageVector by lazy {
        builder("Shutter") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) { circle(12f, 12f, 8.4f) }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.4f, 7.4f); lineTo(17.6f, 16.6f)
                moveTo(17.6f, 7.4f); lineTo(6.4f, 16.6f)
            }
        }
    }

    val Iso: ImageVector by lazy {
        builder("Iso") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) { circle(12f, 12.5f, 3.9f) }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f); lineTo(12f, 5.4f)
                moveTo(12f, 19.6f); lineTo(12f, 22f)
                moveTo(2.5f, 12.5f); lineTo(4.9f, 12.5f)
                moveTo(19.1f, 12.5f); lineTo(21.5f, 12.5f)
                moveTo(5.3f, 5.8f); lineTo(7f, 7.5f)
                moveTo(17f, 17.5f); lineTo(18.7f, 19.2f)
                moveTo(18.7f, 5.8f); lineTo(17f, 7.5f)
                moveTo(7f, 17.5f); lineTo(5.3f, 19.2f)
            }
        }
    }

    val WhiteBalance: ImageVector by lazy {
        builder("WhiteBalance") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10f, 13.8f); lineTo(10f, 5.2f)
                curveTo(10f, 3.4f, 10.9f, 2.2f, 12f, 2.2f)
                curveTo(13.1f, 2.2f, 14f, 3.4f, 14f, 5.2f)
                lineTo(14f, 13.8f)
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) { circle(12f, 16.8f, 3.6f) }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14f, 7f); lineTo(16.5f, 7f)
                moveTo(14f, 10.5f); lineTo(16.5f, 10.5f)
                moveTo(18f, 5f); lineTo(21.5f, 5f)
                moveTo(18f, 12.5f); lineTo(21.5f, 12.5f)
            }
        }
    }

    val FocusTarget: ImageVector by lazy {
        builder("FocusTarget") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 8f); lineTo(4f, 4f); lineTo(8f, 4f)
                moveTo(16f, 4f); lineTo(20f, 4f); lineTo(20f, 8f)
                moveTo(20f, 16f); lineTo(20f, 20f); lineTo(16f, 20f)
                moveTo(8f, 20f); lineTo(4f, 20f); lineTo(4f, 16f)
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) { circle(12f, 12f, 3.4f) }
        }
    }

    val Speed: ImageVector by lazy {
        builder("Speed") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.5f, 17f)
                curveTo(6f, 10.5f, 18f, 10.5f, 19.5f, 17f)
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 15.5f); lineTo(16f, 11f)
            }
        }
    }

    val Mic: ImageVector by lazy {
        builder("Mic") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 12f); lineTo(8.5f, 7f)
                curveTo(8.5f, 4.9f, 10.1f, 3.2f, 12f, 3.2f)
                curveTo(13.9f, 3.2f, 15.5f, 4.9f, 15.5f, 7f)
                lineTo(15.5f, 12f)
                curveTo(15.5f, 14.1f, 13.9f, 15.8f, 12f, 15.8f)
                curveTo(10.1f, 15.8f, 8.5f, 14.1f, 8.5f, 12f)
                close()
                moveTo(12f, 15.8f); lineTo(12f, 19.5f)
                moveTo(8.5f, 19.5f); lineTo(15.5f, 19.5f)
            }
        }
    }

    val Movie: ImageVector by lazy {
        builder("Movie") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.5f, 5f); lineTo(20.5f, 5f)
                lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
            }
            path(fill = Fill) {
                moveTo(10.4f, 8.8f)
                lineTo(15.2f, 12f)
                lineTo(10.4f, 15.2f)
                close()
            }
        }
    }

    val Layers: ImageVector by lazy {
        builder("Layers") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 8f); lineTo(12f, 4f); lineTo(20f, 8f)
                moveTo(4f, 13f); lineTo(12f, 9f); lineTo(20f, 13f)
                moveTo(4f, 18f); lineTo(12f, 14f); lineTo(20f, 18f)
            }
        }
    }

    val Grid: ImageVector by lazy {
        builder("Grid") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.5f, 4.5f); lineTo(19.5f, 4.5f)
                lineTo(19.5f, 19.5f); lineTo(4.5f, 19.5f); close()
                moveTo(12f, 4.5f); lineTo(12f, 19.5f)
                moveTo(4.5f, 12f); lineTo(19.5f, 12f)
            }
        }
    }

    val Record: ImageVector by lazy {
        builder("Record") {
            path(fill = Fill) { circle(12f, 12f, 8f) }
        }
    }

    val Stop: ImageVector by lazy {
        builder("Stop") {
            path(fill = Fill) {
                moveTo(7.5f, 7.5f); lineTo(16.5f, 7.5f)
                lineTo(16.5f, 16.5f); lineTo(7.5f, 16.5f); close()
            }
        }
    }

    val Chevron: ImageVector by lazy {
        builder("Chevron") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
            }
        }
    }

    /** Hand-drawn gear — settings button (same stroke style as the set). */
    val Settings: ImageVector by lazy {
        builder("Settings") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // eight spokes radiating from the core circle
                for (a in 0 until 8) {
                    val ang = Math.toRadians(a * 45.0)
                    val cos = kotlin.math.cos(ang).toFloat()
                    val sin = kotlin.math.sin(ang).toFloat()
                    moveTo(12f + cos * 6.2f, 12f + sin * 6.2f)
                    lineTo(12f + cos * 8.6f, 12f + sin * 8.6f)
                }
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                circle(12f, 12f, 4.6f)
            }
        }
    }

    /** Scopes menu button — stacked layered frames with a waveform. */
    val Scopes: ImageVector by lazy {
        builder("Scopes") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // back frame, offset up-right
                moveTo(8.5f, 5.5f); lineTo(19.5f, 5.5f)
                lineTo(19.5f, 16.5f); lineTo(8.5f, 16.5f); close()
            }
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // front frame with rising waveform line
                moveTo(4.5f, 7.5f); lineTo(15.5f, 7.5f)
                lineTo(15.5f, 18.5f); lineTo(4.5f, 18.5f); close()
                moveTo(6.5f, 15.5f); lineTo(9f, 11.5f); lineTo(11f, 13.5f); lineTo(13.5f, 10.5f)
            }
        }
    }

    val Play: ImageVector by lazy {
        builder("Play") {
            path(fill = Fill) {
                moveTo(7f, 5f)
                lineTo(19f, 12f)
                lineTo(7f, 19f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        builder("Pause") {
            path(fill = Fill) {
                moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
                moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
            }
        }
    }

    val StepBack: ImageVector by lazy {
        builder("StepBack") {
            path(fill = Fill) {
                moveTo(6f, 6f); lineTo(8.5f, 6f); lineTo(8.5f, 18f); lineTo(6f, 18f); close()
                moveTo(18f, 6f); lineTo(10f, 12f); lineTo(18f, 18f); close()
            }
        }
    }

    val StepForward: ImageVector by lazy {
        builder("StepForward") {
            path(fill = Fill) {
                moveTo(15.5f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(15.5f, 18f); close()
                moveTo(6f, 6f); lineTo(14f, 12f); lineTo(6f, 18f); close()
            }
        }
    }

    val Repeat: ImageVector by lazy {
        builder("Repeat") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 7f); lineTo(17f, 7f)
                curveTo(19.2f, 7f, 21f, 8.8f, 21f, 11f)
                lineTo(21f, 12f)
                moveTo(14f, 4f); lineTo(17f, 7f); lineTo(14f, 10f)

                moveTo(17f, 17f); lineTo(7f, 17f)
                curveTo(4.8f, 17f, 3f, 15.2f, 3f, 13f)
                lineTo(3f, 12f)
                moveTo(10f, 20f); lineTo(7f, 17f); lineTo(10f, 14f)
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        builder("VolumeUp") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 9.5f); lineTo(8.5f, 9.5f); lineTo(13f, 5.5f); lineTo(13f, 18.5f); lineTo(8.5f, 14.5f); lineTo(4f, 14.5f); close()
                moveTo(16.5f, 8.5f)
                curveTo(17.8f, 9.5f, 18.5f, 10.7f, 18.5f, 12f)
                curveTo(18.5f, 13.3f, 17.8f, 14.5f, 16.5f, 15.5f)
            }
        }
    }

    val VolumeMute: ImageVector by lazy {
        builder("VolumeMute") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 9.5f); lineTo(8.5f, 9.5f); lineTo(13f, 5.5f); lineTo(13f, 18.5f); lineTo(8.5f, 14.5f); lineTo(4f, 14.5f); close()
                moveTo(16f, 9.5f); lineTo(20.5f, 14.5f)
                moveTo(20.5f, 9.5f); lineTo(16f, 14.5f)
            }
        }
    }

    val Export: ImageVector by lazy {
        builder("Export") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 14f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 14f)
                moveTo(12f, 4f); lineTo(12f, 15f)
                moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        builder("Trash") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f)
                moveTo(9f, 6.5f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 6.5f)
                moveTo(6.5f, 6.5f); lineTo(7.5f, 19f); lineTo(16.5f, 19f); lineTo(17.5f, 6.5f)
                moveTo(10f, 10f); lineTo(10f, 15.5f)
                moveTo(14f, 10f); lineTo(14f, 15.5f)
            }
        }
    }

    val Rotate: ImageVector by lazy {
        builder("Rotate") {
            path(
                fill = null, stroke = Stroke, strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20f, 12f)
                curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
                curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
                curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
                curveTo(15.2f, 4f, 18f, 5.9f, 19.3f, 8.6f)
                moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f)
            }
        }
    }
}

package dev.rawrec.app.probe

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ModeProbe {

    fun run(context: Context): List<String> {
        val out = mutableListOf<String>()
        out += "device : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        out += "soc    : ${Build.HARDWARE} board=${Build.BOARD} sdk=${Build.VERSION.SDK_INT}"

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cams = CameraCatalog.load(context)
        for (cam in cams) {
            val c = if (cam.physicalId != null) {
                runCatching { cm.getCameraCharacteristics(cam.physicalId) }.getOrNull()
                    ?: cm.getCameraCharacteristics(cam.id)
            } else {
                cm.getCameraCharacteristics(cam.id)
            }
            out += ""
            out += "[${cam.displayName}] facing=${cam.facing} hw=${cam.hardwareLevel} raw=${cam.hasRaw}"
            if (cam.focalLength35mmEq > 0f) {
                out += "    optics: ${cam.focalLengthMm}mm (35mm eq: ${cam.focalLength35mmEq.roundToInt()}mm, ${cam.lensRole.label})"
            }
            val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (physicalSize != null) {
                out += "    sensor: ${physicalSize.width}x${physicalSize.height}mm orientation=${cam.sensorOrientation}°"
            }
            if (cam.maxAnalogIso > 0) {
                out += "    maxAnalogIso: ${cam.maxAnalogIso}"
            }

            if (!cam.hasRaw) {
                out += "    RAW_SENSOR: not advertised by firmware"
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val yuvSizes = runCatching { map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty() }.getOrDefault(emptyList())
                if (yuvSizes.isNotEmpty()) {
                    out += "    preview/YUV max: ${fmt(yuvSizes.first())} (${yuvSizes.size} sizes)"
                }
            } else {
                for (s in cam.rawSizes) {
                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val minNs = runCatching { map?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s) ?: 0L }.getOrDefault(0L)
                    val fps = if (minNs > 0) String.format(Locale.US, "%.1f", 1e9 / minNs) else "n/a"
                    out += "    raw ${fmt(s)} maxFPS=$fps"
                }
            }

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hs = map?.highSpeedVideoSizes?.toList().orEmpty()
            if (hs.isNotEmpty()) {
                out += "    highSpeed candidates:"
                for (s in hs) {
                    val ranges = map?.getHighSpeedVideoFpsRangesFor(s)
                        ?.joinToString(",") { "${it.lower}-${it.upper}" }
                    out += "      ${fmt(s)} fpsRanges=[$ranges]"
                }
            }

            val vendorTags = dev.rawrec.app.capture.VendorTags.discoverVendorTags(c)
            if (vendorTags.isNotEmpty()) {
                out += "    vendor tags (${vendorTags.size}):"
                for (tag in vendorTags.take(8)) {
                    out += "      $tag"
                }
                if (vendorTags.size > 8) {
                    out += "      ... +${vendorTags.size - 8} more"
                }
            }
        }
        return out
    }

    fun save(context: Context, lines: List<String>): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "probe_$stamp.txt").apply { writeText(lines.joinToString("\n")) }
    }

    private fun fmt(s: Size) = "${s.width}x${s.height}"
}

package dev.rawrec.app.probe

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.roundToInt

enum class LensRole(val label: String) {
    ULTRAWIDE("Ultrawide"),
    WIDE("Wide"),
    TELEPHOTO("Telephoto"),
    FRONT("Front"),
    UNKNOWN("External")
}

data class CamInfo(
    val id: String,
    val physicalId: String? = null,
    val logicalParentId: String? = null,
    val facing: String,
    val hardwareLevel: String,
    val hasRaw: Boolean,
    val rawSizes: List<Size>,
    val previewSizes: List<Size>,
    val sensorOrientation: Int,
    val whiteLevel: Int,
    val cfaPattern: Int,
    val exposureRangeNs: android.util.Range<Long>?,
    val isoRange: android.util.Range<Int>?,
    val maxAnalogIso: Int = 0,
    val supportedFps: List<Double> = listOf(24.0, 30.0),
    val focalLengthMm: Float = 0f,
    val focalLength35mmEq: Float = 0f,
    val lensRole: LensRole = LensRole.WIDE
) {
    /**
     * Professional lens and source label, e.g.:
     * "cam0 · 24mm Wide" or "cam0:2 · 14mm Ultrawide" or "cam1 · Front"
     */
    val displayName: String
        get() = buildString {
            append("cam$id")
            if (physicalId != null) append(":$physicalId")
            append(" · ")
            if (focalLength35mmEq > 0f) append("${focalLength35mmEq.roundToInt()}mm ")
            append(lensRole.label)
        }
}

object CameraCatalog {

    /** Common non-advertised vendor auxiliary camera IDs probed to bypass OEM whitelists */
    val CANDIDATE_AUX_IDS = listOf("2", "3", "4", "5", "6", "20", "21", "22", "60", "61", "100", "101")

    fun computeFocalLength35mmEq(minFocalLength: Float, physicalWidthMm: Float, physicalHeightMm: Float): Float {
        val diagonalMm = Math.hypot(physicalWidthMm.toDouble(), physicalHeightMm.toDouble()).toFloat()
        if (diagonalMm <= 0f || minFocalLength <= 0f) return 0f
        // 43.3mm is the standard diagonal of 35mm full-frame film (36mm x 24mm)
        return (43.3f / diagonalMm) * minFocalLength
    }

    fun classifyLensRole(facing: String, focal35mmEq: Float): LensRole {
        if (facing == "front") return LensRole.FRONT
        if (focal35mmEq <= 0f) return LensRole.UNKNOWN
        return when {
            focal35mmEq < 24f -> LensRole.ULTRAWIDE
            focal35mmEq <= 35f -> LensRole.WIDE
            focal35mmEq > 35f -> LensRole.TELEPHOTO
            else -> LensRole.WIDE
        }
    }

    fun load(context: Context): List<CamInfo> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val standardIds = runCatching { cm.cameraIdList.toList() }.getOrDefault(listOf("0", "1"))
        val probedAuxIds = CANDIDATE_AUX_IDS.filter { it !in standardIds }.filter { id ->
            runCatching { cm.getCameraCharacteristics(id) }.isSuccess
        }
        val allStandaloneIds = (standardIds + probedAuxIds).distinct()

        val result = mutableListOf<CamInfo>()
        for (id in allStandaloneIds) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val info = runCatching {
                parseCamInfo(openId = id, physicalId = null, logicalParentId = null, c = c)
            }.onFailure { err ->
                dev.rawrec.app.util.AppLog.w("CameraCatalog", "failed to parse standalone camera $id: ${err.message}")
            }.getOrNull()
            if (info != null) result.add(info)

            // Inspect logical multi-camera physical cameras (Android 9+ / API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val caps = runCatching { c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty() }.getOrDefault(emptyList())
                if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                    val physicalIds = runCatching { c.physicalCameraIds }.getOrDefault(emptySet())
                    for (physId in physicalIds) {
                        if (allStandaloneIds.none { it == physId } && result.none { it.id == id && it.physicalId == physId }) {
                            val physChars = runCatching {
                                cm.getCameraCharacteristics(physId)
                            }.getOrNull()
                            if (physChars != null) {
                                val physInfo = runCatching {
                                    parseCamInfo(
                                        openId = id,
                                        physicalId = physId,
                                        logicalParentId = id,
                                        c = physChars
                                    )
                                }.onFailure { err ->
                                    dev.rawrec.app.util.AppLog.w("CameraCatalog", "failed to parse physical camera $id:$physId: ${err.message}")
                                }.getOrNull()
                                if (physInfo != null) {
                                    result.add(physInfo)
                                }
                            }
                        }
                    }
                }
            }
        }
        val physicalOnly = filterPhysicalSensors(result)
        dev.rawrec.app.util.AppLog.i("CameraCatalog", "discovered physical cameras: ${physicalOnly.map { "${it.displayName} (hasRaw=${it.hasRaw}, hw=${it.hardwareLevel})" }}")
        return physicalOnly
    }

    /**
     * Filters camera devices to only include real, distinct physical sensors.
     * 1. Rejects external, dummy, or virtual test stream ports (facing must be "back" or "front").
     * 2. Rejects cameras with zero or invalid optical sensor dimensions.
     * 3. Deduplicates virtual/synthetic pipeline clones that share identical physical lens
     *    characteristics (facing and 35mm equivalent focal length) on the same facing.
     */
    fun filterPhysicalSensors(cams: List<CamInfo>): List<CamInfo> {
        val distinctSensors = mutableListOf<CamInfo>()
        for (cam in cams) {
            // 1. Only include built-in physical lenses (back or front)
            if (cam.facing != "back" && cam.facing != "front") continue
            // 2. Must have valid optical properties
            if (cam.focalLengthMm <= 0f || cam.focalLength35mmEq <= 0f) continue

            // 3. Deduplicate virtual/synthetic pipeline clones:
            // If another camera already exists on the same facing with identical focal length,
            // the earlier primary device (e.g. cam0 vs cam3/cam4) is the real hardware entry.
            val isDuplicate = distinctSensors.any { existing ->
                existing.facing == cam.facing &&
                kotlin.math.abs(existing.focalLength35mmEq - cam.focalLength35mmEq) < 1.0f
            }
            if (!isDuplicate) {
                distinctSensors.add(cam)
            }
        }
        return distinctSensors
    }

    private fun parseCamInfo(
        openId: String,
        physicalId: String?,
        logicalParentId: String?,
        c: CameraCharacteristics
    ): CamInfo {
        val caps = runCatching { c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty() }.getOrDefault(emptyList())
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val map = runCatching { c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull()
        val sizes = if (hasRaw && map != null) {
            val stdSizes = runCatching {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val highResSizes = runCatching {
                map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val maxResSizes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching {
                    val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
                }.getOrDefault(emptyList())
            } else emptyList()
            (stdSizes + highResSizes + maxResSizes)
                .distinct()
                .sortedByDescending { it.width.toLong() * it.height }
        } else emptyList()
        val previewSizes = if (map != null) {
            runCatching {
                map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
                    .sortedByDescending { it.width.toLong() * it.height }
            }.getOrDefault(emptyList())
        } else emptyList()
        val maxAnalog = runCatching { c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) }.getOrNull() ?: 0
        val fpsRanges = runCatching { c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty() }.getOrDefault(emptyList())
        val availableFps = fpsRanges
            .map { it.upper.toDouble() }
            .filter { it in 15.0..120.0 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(24.0, 30.0) }

        val facing = when (runCatching { c.get(CameraCharacteristics.LENS_FACING) }.getOrNull()) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "ext"
        }

        val focalLengths = runCatching { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) }.getOrNull()
        val minFocal = focalLengths?.minOrNull() ?: 0f
        val physicalSize = runCatching { c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }.getOrNull()
        val focal35mmEq = if (physicalSize != null) {
            computeFocalLength35mmEq(minFocal, physicalSize.width, physicalSize.height)
        } else 0f
        val role = classifyLensRole(facing, focal35mmEq)

        return CamInfo(
            id = openId,
            physicalId = physicalId,
            logicalParentId = logicalParentId,
            facing = facing,
            hardwareLevel = when (runCatching { c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }.getOrNull()) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "L3"
                else -> "?"
            },
            hasRaw = hasRaw,
            rawSizes = sizes,
            previewSizes = previewSizes,
            sensorOrientation = runCatching { c.get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull() ?: 90,
            whiteLevel = runCatching { c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) }.getOrNull() ?: 1023,
            cfaPattern = runCatching { c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) }.getOrNull() ?: 0,
            exposureRangeNs = runCatching { c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }.getOrNull(),
            isoRange = runCatching { c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }.getOrNull(),
            maxAnalogIso = maxAnalog,
            supportedFps = availableFps,
            focalLengthMm = minFocal,
            focalLength35mmEq = focal35mmEq,
            lensRole = role
        )
    }

    fun formatResolution(size: Size, allSizes: List<Size> = emptyList()): String {
        val mp = (size.width.toLong() * size.height) / 1_000_000.0
        val maxArea = allSizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
        val isMax = size.width.toLong() * size.height == maxArea && allSizes.size > 1
        val label = when {
            isMax && mp >= 40.0 -> "${String.format(java.util.Locale.US, "%.0f", mp)}MP Full"
            mp in 10.0..15.0 -> "${String.format(java.util.Locale.US, "%.1f", mp)}MP Binned"
            size.width == 3840 && size.height == 2160 -> "4K"
            size.width == 1920 && size.height == 1080 -> "1080p"
            mp >= 1.0 -> "${String.format(java.util.Locale.US, "%.1f", mp)}MP"
            else -> "${size.width}×${size.height}"
        }
        return "${size.width}×${size.height} ($label)"
    }

    fun formatShortResolution(size: Size): String {
        val mp = (size.width.toLong() * size.height) / 1_000_000.0
        return when {
            mp >= 40.0 -> "${String.format(java.util.Locale.US, "%.0f", mp)}MP"
            mp in 10.0..15.0 -> "${String.format(java.util.Locale.US, "%.1f", mp)}MP"
            size.width == 3840 && size.height == 2160 -> "4K"
            size.width == 1920 && size.height == 1080 -> "1080p"
            else -> String.format(java.util.Locale.US, "%.1fMP", mp)
        }
    }
}

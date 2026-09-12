package dev.rawrec.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Size
import dev.rawrec.app.control.CameraControlState
import dev.rawrec.app.ui.ViewfinderMath.OrientationMode

/**
 * Lightweight, persistent configuration repository for RawRec.
 * Backed by Android SharedPreferences for synchronous startup reads and fast auto-save.
 */
class AppPreferences(
    context: Context? = null,
    customPrefs: SharedPreferences? = null
) {
    private val prefs: SharedPreferences = customPrefs
        ?: context?.getSharedPreferences("rawrec_preferences", Context.MODE_PRIVATE)
        ?: throw IllegalArgumentException("Either context or customPrefs must be provided")

    var cameraId: String?
        get() = prefs.getString("pref_camera_id", null)
        set(value) = prefs.edit().putString("pref_camera_id", value).apply()

    var rawWidth: Int
        get() = prefs.getInt("pref_raw_width", 0)
        set(value) = prefs.edit().putInt("pref_raw_width", value).apply()

    var rawHeight: Int
        get() = prefs.getInt("pref_raw_height", 0)
        set(value) = prefs.edit().putInt("pref_raw_height", value).apply()

    var rawSize: Size?
        get() {
            val w = rawWidth
            val h = rawHeight
            return if (w > 0 && h > 0) runCatching { Size(w, h) }.getOrNull() else null
        }
        set(value) {
            if (value != null) {
                runCatching {
                    rawWidth = value.width
                    rawHeight = value.height
                }
            } else {
                rawWidth = 0
                rawHeight = 0
            }
        }

    var useZstd: Boolean
        get() = prefs.getBoolean("pref_use_zstd", true)
        set(value) = prefs.edit().putBoolean("pref_use_zstd", value).apply()

    var packMipi: Boolean
        get() = prefs.getBoolean("pref_pack_mipi", true)
        set(value) = prefs.edit().putBoolean("pref_pack_mipi", value).apply()

    var micOn: Boolean
        get() = prefs.getBoolean("pref_mic_on", false)
        set(value) = prefs.edit().putBoolean("pref_mic_on", value).apply()

    var proxyOn: Boolean
        get() = prefs.getBoolean("pref_proxy_on", false)
        set(value) = prefs.edit().putBoolean("pref_proxy_on", value).apply()

    var orientationMode: OrientationMode
        get() = runCatching {
            OrientationMode.valueOf(prefs.getString("pref_orientation_mode", "CINEMA_LANDSCAPE")!!)
        }.getOrDefault(OrientationMode.CINEMA_LANDSCAPE)
        set(value) = prefs.edit().putString("pref_orientation_mode", value.name).apply()

    var fillFraction: Float
        get() = prefs.getFloat("pref_fill_fraction", 0f)
        set(value) = prefs.edit().putFloat("pref_fill_fraction", value).apply()

    var autoDisableVfSeconds: Int
        get() = prefs.getInt("pref_auto_disable_vf_seconds", 0) // 0 = never
        set(value) = prefs.edit().putInt("pref_auto_disable_vf_seconds", value).apply()

    /** Anamorphic stretch presentation: whole frame fills the screen, no crop (non-square pixels). */
    var presentationStretch: Boolean
        get() = prefs.getBoolean("pref_presentation_stretch", false)
        set(value) = prefs.edit().putBoolean("pref_presentation_stretch", value).apply()

    fun loadCameraControlState(): CameraControlState = CameraControlState(
        iso = prefs.getInt("pref_iso", 100),
        autoIso = prefs.getBoolean("pref_auto_iso", false),
        exposureNs = prefs.getLong("pref_exposure_ns", 20_000_000L),
        autoExposure = prefs.getBoolean("pref_auto_exposure", false),
        shutterAngle = prefs.getFloat("pref_shutter_angle", 180f).toDouble(),
        useShutterAngle = prefs.getBoolean("pref_use_shutter_angle", false),
        focusDiopters = prefs.getFloat("pref_focus_diopters", 0f),
        autoFocus = prefs.getBoolean("pref_auto_focus", true),
        whiteBalanceKelvin = prefs.getInt("pref_wb_kelvin", 5600),
        whiteBalanceTint = prefs.getInt("pref_wb_tint", 0),
        autoWhiteBalance = prefs.getBoolean("pref_auto_wb", true),
        targetFps = prefs.getFloat("pref_target_fps", 30f).toDouble(),
        aspectIndex = prefs.getInt("pref_aspect_index", 0)
    )

    fun saveCameraControlState(state: CameraControlState) {
        prefs.edit()
            .putInt("pref_iso", state.iso)
            .putBoolean("pref_auto_iso", state.autoIso)
            .putLong("pref_exposure_ns", state.exposureNs)
            .putBoolean("pref_auto_exposure", state.autoExposure)
            .putFloat("pref_shutter_angle", state.shutterAngle.toFloat())
            .putBoolean("pref_use_shutter_angle", state.useShutterAngle)
            .putFloat("pref_focus_diopters", state.focusDiopters)
            .putBoolean("pref_auto_focus", state.autoFocus)
            .putInt("pref_wb_kelvin", state.whiteBalanceKelvin)
            .putInt("pref_wb_tint", state.whiteBalanceTint)
            .putBoolean("pref_auto_wb", state.autoWhiteBalance)
            .putFloat("pref_target_fps", state.targetFps.toFloat())
            .putInt("pref_aspect_index", state.aspectIndex)
            .apply()
    }
}

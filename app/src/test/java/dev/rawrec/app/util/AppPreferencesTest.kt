package dev.rawrec.app.util

import android.content.SharedPreferences
import dev.rawrec.app.control.CameraControlState
import dev.rawrec.app.ui.ViewfinderMath.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = values
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) temp[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply {
                map.clear()
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                temp.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    @Test
    fun `default values match expected application settings`() {
        val prefs = AppPreferences(customPrefs = FakeSharedPreferences())
        assertTrue(prefs.useZstd)
        assertTrue(prefs.packMipi)
        assertFalse(prefs.micOn)
        assertFalse(prefs.proxyOn)
        assertEquals(OrientationMode.CINEMA_LANDSCAPE, prefs.orientationMode)
        assertEquals(0f, prefs.fillFraction, 0.001f)
        assertEquals(0, prefs.autoDisableVfSeconds)
        assertNull(prefs.cameraId)
        assertNull(prefs.rawSize)

        val controls = prefs.loadCameraControlState()
        assertEquals(100, controls.iso)
        assertFalse(controls.autoExposure)
        assertEquals(20_000_000L, controls.exposureNs)
        assertEquals(180.0, controls.shutterAngle, 0.001)
        assertFalse(controls.useShutterAngle)
        assertEquals(0.0f, controls.focusDiopters, 0.001f)
        assertTrue(controls.autoFocus)
        assertEquals(5600, controls.whiteBalanceKelvin)
        assertTrue(controls.autoWhiteBalance)
        assertEquals(30.0, controls.targetFps, 0.001)
        assertEquals(0, controls.aspectIndex)
    }

    @Test
    fun `toggles and camera source selections persist and update cleanly`() {
        val fake = FakeSharedPreferences()
        val prefs = AppPreferences(customPrefs = fake)

        prefs.useZstd = false
        prefs.packMipi = false
        prefs.micOn = true
        prefs.proxyOn = true
        prefs.orientationMode = OrientationMode.CHASSIS_LOCKED
        prefs.fillFraction = 0.75f
        prefs.presentationStretch = true
        prefs.autoDisableVfSeconds = 10
        prefs.cameraId = "0"
        prefs.rawWidth = 4096
        prefs.rawHeight = 3072

        // Read through a fresh AppPreferences instance pointing to the same storage
        val fresh = AppPreferences(customPrefs = fake)
        assertFalse(fresh.useZstd)
        assertFalse(fresh.packMipi)
        assertTrue(fresh.micOn)
        assertTrue(fresh.proxyOn)
        assertEquals(OrientationMode.CHASSIS_LOCKED, fresh.orientationMode)
        assertEquals(0.75f, fresh.fillFraction, 0.001f)
        assertTrue(fresh.presentationStretch)
        assertEquals(10, fresh.autoDisableVfSeconds)
        assertEquals("0", fresh.cameraId)
        assertEquals(4096, fresh.rawWidth)
        assertEquals(3072, fresh.rawHeight)
    }

    @Test
    fun `camera control state persists all photographic parameters accurately`() {
        val fake = FakeSharedPreferences()
        val prefs = AppPreferences(customPrefs = fake)

        val customized = CameraControlState(
            iso = 400,
            autoIso = false,
            exposureNs = 10_000_000L, // 1/100s
            autoExposure = false,
            shutterAngle = 144.0,
            useShutterAngle = true,
            focusDiopters = 2.5f,
            autoFocus = false,
            whiteBalanceKelvin = 4200,
            whiteBalanceTint = 12,
            autoWhiteBalance = false,
            targetFps = 24.0,
            aspectIndex = 1 // 2.39:1
        )

        prefs.saveCameraControlState(customized)

        val fresh = AppPreferences(customPrefs = fake)
        val loaded = fresh.loadCameraControlState()

        assertEquals(400, loaded.iso)
        assertFalse(loaded.autoIso)
        assertEquals(10_000_000L, loaded.exposureNs)
        assertFalse(loaded.autoExposure)
        assertEquals(144.0, loaded.shutterAngle, 0.001)
        assertTrue(loaded.useShutterAngle)
        assertEquals(2.5f, loaded.focusDiopters, 0.001f)
        assertFalse(loaded.autoFocus)
        assertEquals(4200, loaded.whiteBalanceKelvin)
        assertEquals(12, loaded.whiteBalanceTint)
        assertFalse(loaded.autoWhiteBalance)
        assertEquals(24.0, loaded.targetFps, 0.001)
        assertEquals(1, loaded.aspectIndex)
    }
}

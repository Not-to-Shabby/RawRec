package dev.rawrec.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Tracks the physical device orientation via the accelerometer and derives
 * the UI rotation angle — the app's OWN rotation engine.
 *
 * The activity is locked to portrait (screenOrientation="portrait"); the
 * system never rotates the window. Instead this tracker reports which way
 * the device is physically held and the UI rotates itself via graphicsLayer.
 *
 * Hysteresis: orientation must be held ~150ms past the 45° boundary before
 * switching (prevents flip-flop when holding the device near diagonal).
 */
class DeviceOrientationTracker(
    context: Context,
    private val onChange: (String, Int) -> Unit
) {
    /** UI rotation angle in degrees (0, 90, 180, 270) — clockwise, to rotate chrome. */
    var uiRotation = 0
        private set

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var registered = false

    private var pendingOrientation = -1
    private var pendingSinceMs = 0L
    private var currentOrientation = -1

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Lying flat: keep last orientation (like real cameras do)
            if (kotlin.math.abs(z) > 6.5f) return

            // Dominant axis -> orientation index
            val candidate = when {
                y > 6.5f -> 0      // upright portrait
                x > 6.5f -> 90      // rotated left (top of device points left)
                y < -6.5f -> 180    // upside down
                x < -6.5f -> 270    // rotated right
                else -> return      // diagonal — hold last
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (candidate != pendingOrientation) {
                pendingOrientation = candidate
                pendingSinceMs = now
                return
            }
            // Require stability before committing (hysteresis)
            if (currentOrientation != candidate && now - pendingSinceMs > 150L) {
                currentOrientation = candidate
                uiRotation = candidate
                onChange(nameFor(candidate), candidate)
            } else if (currentOrientation == candidate) {
                // Re-report on registration or recovery
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun nameFor(deg: Int): String = when (deg) {
        0 -> "portrait"
        90 -> "landscape-left"
        180 -> "portrait-inverse"
        270 -> "landscape-right"
        else -> "unknown"
    }

    fun start() {
        if (sensor != null && !registered) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            registered = true
        }
    }

    fun stop() {
        if (registered) {
            sm.unregisterListener(listener)
            registered = false
        }
    }
}

/** Display rotation constant -> descriptive string (for the debug card). */
fun rotationName(rotation: Int): String = when (rotation) {
    Surface.ROTATION_0 -> "portrait (0°)"
    Surface.ROTATION_90 -> "landscape (90°)"
    Surface.ROTATION_180 -> "portrait-inverse (180°)"
    Surface.ROTATION_270 -> "landscape (270°)"
    else -> "unknown ($rotation°)"
}

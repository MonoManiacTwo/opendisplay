package app.opendisplay.android.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Shake opens settings anytime, as on iOS. Two hard jolts within 600 ms. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastJolt = 0L
    private var lastShake = 0L

    fun start() {
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.5f) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastJolt in 80..600 && now - lastShake > 1500) {
            lastShake = now
            onShake()
        }
        lastJolt = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

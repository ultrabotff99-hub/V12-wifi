package com.tonix.wifireader

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Direction the camera points (degrees, 0 = true north, 90 = east). NaN when the phone has no compass. */
class Compass(ctx: Context) : SensorEventListener {
    private val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile var heading = Float.NaN
    @Volatile var declination = 0f
    @Volatile var accuracy = 3                 // 0-1 = calibrate the compass (draw a figure 8), 2-3 = ok
    private val hT = LongArray(900)
    private val hH = FloatArray(900)
    private var hN = 0
    private var hLast = 0L
    private val r1 = FloatArray(9)
    private val r2 = FloatArray(9)
    private val o = FloatArray(3)
    private var sinS = 0f
    private var cosS = 0f
    private var first = true
    private var on = false

    fun start() {
        if (on) return
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        on = true
    }

    fun stop() { sm.unregisterListener(this); on = false }

    fun setLocation(l: Location) {
        try { declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination } catch (e: Exception) { }
    }

    override fun onSensorChanged(e: SensorEvent) {
        try {
            SensorManager.getRotationMatrixFromVector(r1, e.values)
            // Direction the BACK CAMERA points = device -Z axis expressed in world coordinates (x east, y north, z up).
            // This stays correct when the phone is tilted up/down; the old "upright only" remap drifted as soon as it was tilted.
            val east = -r1[2]; val north = -r1[5]
            if (kotlin.math.hypot(east, north) < 0.3f) return          // camera looks (almost) straight up/down: heading undefined, keep last
            val rad = atan2(east, north)
            if (first) { sinS = sin(rad); cosS = cos(rad); first = false }
            else { sinS += (sin(rad) - sinS) * 0.18f; cosS += (cos(rad) - cosS) * 0.18f }   // smooth on the circle
            val deg = Math.toDegrees(atan2(sinS, cosS).toDouble()).toFloat() + declination
            heading = ((deg % 360f) + 360f) % 360f
            val now = SystemClock.elapsedRealtime()
            if (now - hLast >= 100) { hLast = now; val i = hN % 900; hT[i] = now; hH[i] = heading; hN++ }
        } catch (ex: Exception) { }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { this.accuracy = accuracy }

    /** heading the camera had at time tMs (SystemClock.elapsedRealtime base); NaN if we have no record of that moment */
    fun headingAt(tMs: Long): Float {
        val n = if (hN < 900) hN else 900
        if (n == 0) return Float.NaN
        var best = -1L; var bi = 0
        for (i in 0 until n) { val dd = Math.abs(hT[i] - tMs); if (best < 0 || dd < best) { best = dd; bi = i } }
        return if (best > 3000) Float.NaN else hH[bi]
    }
}

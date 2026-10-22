package com.trifold.xrealxr.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Turns the Samsung Galaxy Z TriFold into a 3DoF spatial laser pointer controller
 * using the phone's high-rate hardware GAME_ROTATION_VECTOR IMU sensor.
 */
class PhoneLaserController(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var rawPhoneQuat = Quaternion()

    @Volatile
    private var tareOffsetQuat = Quaternion()

    @Volatile
    var isLaserEnabled: Boolean = true

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Aligns the phone's current physical pointing pose with the user's current forward gaze quaternion.
     */
    fun recenterLaser(currentHeadQuat: Quaternion = Quaternion()) {
        // So that (tareOffsetQuat * rawPhoneQuat) == currentHeadQuat when recentered
        tareOffsetQuat = (currentHeadQuat * rawPhoneQuat.conjugate()).normalized()
    }

    /**
     * Returns the calibrated 3DoF orientation quaternion of the phone laser pointer in world space.
     */
    fun getLaserOrientation(): Quaternion {
        return (tareOffsetQuat * rawPhoneQuat).normalized()
    }

    /**
     * Returns the unit direction vector [dx, dy, dz] of the laser ray in OpenGL world space (-Z forward).
     */
    fun getLaserRayDirection(): FloatArray {
        return getLaserOrientation().rotateVector(0f, 0f, -1f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
        ) {
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, event.values)
            // Android SensorManager returns [w, x, y, z] in ENU frame; map to OpenGL camera frame
            rawPhoneQuat = Quaternion(
                w = q[0],
                x = q[1],
                y = q[3],
                z = -q[2]
            ).normalized()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

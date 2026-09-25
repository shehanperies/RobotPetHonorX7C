package com.shehan.robotpet.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.shehan.robotpet.brain.PhoneEvent
import com.shehan.robotpet.brain.PhoneObservation
import kotlin.math.abs
import kotlin.math.sqrt

class PhoneSensorManager(
    context: Context,
    private val onObservation: (PhoneObservation) -> Unit
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var pitchDeg = 0f
    private var rollDeg = 0f
    private var gForce = 1f
    private var lux: Float? = null

    private var lastEmitMs = 0L
    private var lastShakeMs = 0L
    private var lastOrientationEvent = PhoneEvent.NONE
    private var lastLightEvent = PhoneEvent.NONE

    fun start() {
        accelerometer?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotation?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        light?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        var detected = PhoneEvent.NONE

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

                if (gForce > 2.25f && now - lastShakeMs > 1400L) {
                    lastShakeMs = now
                    detected = PhoneEvent.SHAKE
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)

                pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()

                val orientationEvent = when {
                    abs(rollDeg) > 135f -> PhoneEvent.UPSIDE_DOWN
                    rollDeg > 45f -> PhoneEvent.TILT_RIGHT
                    rollDeg < -45f -> PhoneEvent.TILT_LEFT
                    else -> PhoneEvent.NONE
                }

                if (orientationEvent != PhoneEvent.NONE &&
                    orientationEvent != lastOrientationEvent &&
                    now - lastEmitMs > 1200L
                ) {
                    detected = orientationEvent
                }
                lastOrientationEvent = orientationEvent
            }

            Sensor.TYPE_LIGHT -> {
                lux = event.values.firstOrNull()
                val lightEvent = when {
                    (lux ?: 100f) < 5f -> PhoneEvent.DARK
                    (lux ?: 100f) > 2500f -> PhoneEvent.BRIGHT
                    else -> PhoneEvent.NONE
                }

                if (lightEvent != PhoneEvent.NONE &&
                    lightEvent != lastLightEvent &&
                    now - lastEmitMs > 5000L
                ) {
                    detected = lightEvent
                }
                lastLightEvent = lightEvent
            }
        }

        if (detected != PhoneEvent.NONE) {
            lastEmitMs = now
            onObservation(
                PhoneObservation(
                    event = detected,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    gForce = gForce,
                    lux = lux,
                    timestampMs = now
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

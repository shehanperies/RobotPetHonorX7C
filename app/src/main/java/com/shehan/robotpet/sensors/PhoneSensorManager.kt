package com.shehan.robotpet.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.shehan.robotpet.brain.PhoneEvent
import com.shehan.robotpet.brain.PhoneObservation
import kotlin.math.sqrt

class PhoneSensorManager(
    context: Context,
    private val onObservation: (PhoneObservation) -> Unit
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var gForce = 1f
    private var lux: Float? = null
    private var lastShakeMs = 0L
    private var lastLightEvent = PhoneEvent.NONE
    private var lastLightEmitMs = 0L

    fun start() {
        accelerometer?.let {
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

                if (gForce > 2.55f && now - lastShakeMs > 2200L) {
                    lastShakeMs = now
                    detected = PhoneEvent.SHAKE
                }
            }

            Sensor.TYPE_LIGHT -> {
                lux = event.values.firstOrNull()
                val lightEvent = when {
                    (lux ?: 100f) < 4f -> PhoneEvent.DARK
                    (lux ?: 100f) > 3000f -> PhoneEvent.BRIGHT
                    else -> PhoneEvent.NONE
                }

                if (
                    lightEvent != PhoneEvent.NONE &&
                    lightEvent != lastLightEvent &&
                    now - lastLightEmitMs > 8000L
                ) {
                    detected = lightEvent
                    lastLightEmitMs = now
                }
                lastLightEvent = lightEvent
            }
        }

        if (detected != PhoneEvent.NONE) {
            onObservation(
                PhoneObservation(
                    event = detected,
                    pitchDeg = 0f,
                    rollDeg = 0f,
                    gForce = gForce,
                    lux = lux,
                    timestampMs = now
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

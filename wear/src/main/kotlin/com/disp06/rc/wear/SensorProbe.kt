package com.disp06.rc.wear

import android.hardware.Sensor
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds the best heart-signal source. Raw PPG is not part of the public Android sensor API;
 * some vendors expose it as a private sensor type (>= 65536) whose name/stringType mentions
 * "ppg". If nothing like that exists we fall back to Sensor.TYPE_HEART_RATE (≈1 Hz BPM), which
 * gives heart rate but NOT beat-to-beat intervals, so HRV cannot be computed in that mode.
 */
object SensorProbe {
    data class Choice(val sensor: Sensor?, val isRawPpg: Boolean)

    fun choose(sm: SensorManager, override: Int): Choice {
        if (override > 0) {
            preferWakeUp(sm, override)?.let { return Choice(it, override != Sensor.TYPE_HEART_RATE) }
        }
        if (override == 0) {
            val candidates = sm.getSensorList(Sensor.TYPE_ALL).filter {
                it.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE && "${it.name} ${it.stringType}".lowercase().contains("ppg")
            }
            val best = candidates.sortedWith(
                compareByDescending<Sensor> { "${it.name} ${it.stringType}".lowercase().contains("raw") }
                    .thenByDescending { it.isWakeUpSensor }
            ).firstOrNull()
            if (best != null) return Choice(best, true)
        }
        return Choice(preferWakeUp(sm, Sensor.TYPE_HEART_RATE), false)
    }

    /** Wake-up variant if the HAL has one: lets the CPU sleep while the sensor FIFO fills. */
    fun preferWakeUp(sm: SensorManager, type: Int): Sensor? {
        val list = sm.getSensorList(type)
        return list.firstOrNull { it.isWakeUpSensor } ?: list.firstOrNull() ?: sm.getDefaultSensor(type)
    }

    fun describeAll(sm: SensorManager): JSONArray = JSONArray().apply {
        sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
            put(JSONObject().apply {
                put("name", s.name)
                put("type", s.type)
                put("stringType", s.stringType)
                put("vendor", s.vendor)
                put("wakeUp", s.isWakeUpSensor)
                put("minDelayUs", s.minDelay)
                put("fifo", s.fifoMaxEventCount)
            })
        }
    }
}

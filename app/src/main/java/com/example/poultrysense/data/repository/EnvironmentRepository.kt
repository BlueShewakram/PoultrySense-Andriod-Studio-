package com.example.poultrysense.data.repository

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EnvironmentRepository {

    private val realtimeDb: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance("https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app")
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())

    /**
     * Updates live sensor telemetry in Firebase Realtime Database.
     *
     * Storage Optimization:
     * Overwrites /sensor_data/live in-place.
     * Generates a clear, human-readable timestamp (e.g., "13 Sep 2026, 06:46 PM").
     */
    suspend fun syncEnvironmentData(
        temperature: Double,
        humidity: Double,
        zone: String = "Barn Alpha • Zone 1"
    ) {
        try {
            val status = getClimateStatus(temperature, humidity)
            val readableTimestamp = dateFormat.format(Date())

            val liveData = mapOf<String, Any>(
                "temperature" to temperature,
                "humidity" to humidity,
                "zone" to zone,
                "status" to status,
                "timestamp" to readableTimestamp
            )

            // Overwrite in-place: constant ~180 bytes forever
            realtimeDb.getReference("sensor_data")
                .child("live")
                .setValue(liveData)
                .await()

        } catch (_: Exception) {
            // Fails gracefully when offline
        }
    }

    // ─── Calibrated Agronomic Thresholds (Adult Broilers) ───
    const val TEMP_CRITICAL_HEAT = 36.0
    const val TEMP_HEAT_WATCH = 33.0
    const val TEMP_COLD_WATCH = 17.0

    const val HUM_CRITICAL = 88.0
    const val HUM_WATCH = 82.0
    const val HUM_DRY_AIR = 35.0

    /**
     * Unified RRL-grounded climate status evaluation.
     */
    fun getClimateStatus(temp: Double, hum: Double): String {
        return when {
            temp >= TEMP_CRITICAL_HEAT -> "CRITICAL HEAT"
            temp >= TEMP_HEAT_WATCH -> "HEAT WATCH"
            temp <= TEMP_COLD_WATCH -> "COLD WATCH"
            hum >= HUM_CRITICAL -> "CRITICAL HUMIDITY"
            hum >= HUM_WATCH -> "HUMIDITY WATCH"
            hum <= HUM_DRY_AIR -> "DRY AIR"
            else -> "NORMAL"
        }
    }

    fun getTemperatureState(temp: Double): String {
        return when {
            temp >= TEMP_CRITICAL_HEAT -> "Critical"
            temp >= TEMP_HEAT_WATCH -> "Watch"
            temp <= TEMP_COLD_WATCH -> "Low"
            else -> "Normal"
        }
    }

    fun getHumidityState(hum: Double): String {
        return when {
            hum >= HUM_CRITICAL -> "Critical"
            hum >= HUM_WATCH -> "Watch"
            hum <= HUM_DRY_AIR -> "Low"
            else -> "Normal"
        }
    }
}

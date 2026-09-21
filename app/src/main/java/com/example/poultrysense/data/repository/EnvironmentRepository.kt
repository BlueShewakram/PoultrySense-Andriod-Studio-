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

    /**
     * RRL-grounded climate status thresholds for adult broilers (Week 4+).
     *
     * References:
     *   - Dawkins et al. (2004): housing conditions > stocking density for welfare
     *   - Industry consensus: optimal adult broiler range 18–24°C, 50–70% RH
     *   - Heat stress panting onset: ~30°C
     *   - Critical heat stroke risk: ≥35°C
     *   - Wet litter / footpad dermatitis: RH ≥80%
     */
    fun getClimateStatus(temp: Double, hum: Double): String {
        return when {
            temp >= 35.0 -> "CRITICAL HEAT"
            temp >= 30.0 -> "HEAT WATCH"
            temp <= 18.0 -> "COLD WATCH"
            hum >= 80.0 -> "CRITICAL HUMIDITY"
            hum >= 75.0 -> "HUMIDITY WATCH"
            hum <= 40.0 -> "DRY AIR"

            else -> "NORMAL"
        }
    }
}

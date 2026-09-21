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

            val liveData = mapOf(
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

    fun getClimateStatus(temp: Double, hum: Double): String {
        return when {
            temp >= 36.0 -> "CRITICAL HEAT"
            temp >= 33.0 -> "HEAT WATCH"
            temp <= 17.0 -> "COLD WATCH"
            hum >= 88.0 -> "CRITICAL HUMIDITY"
            hum >= 82.0 -> "HUMIDITY WATCH"
            hum <= 35.0 -> "DRY AIR"
            else -> "NORMAL"
        }
    }
}

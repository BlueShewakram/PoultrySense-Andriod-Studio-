package com.example.poultrysense.data

import com.google.firebase.database.DataSnapshot
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ESP32 telemetry freshness check.
 *
 * The firmware writes a human-readable timestamp such as
 * "28 Oct 2026, 09:20:15 PM" every ~3 seconds. A value left in Firebase after
 * the ESP32 is powered off must NOT be shown as a live reading.
 */
object SensorTelemetry {

    private const val MAX_AGE_MS = 25_000L
    private const val MAX_FUTURE_SKEW_MS = 120_000L
    private val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH)

    @Synchronized
    fun isFresh(snapshot: DataSnapshot): Boolean {
        if (!snapshot.exists()) return false
        val timestamp = snapshot.child("timestamp").getValue(String::class.java)
            ?: return false
        val parsed = try {
            formatter.parse(timestamp)
        } catch (_: Exception) {
            null
        } ?: return false

        val ageMs = System.currentTimeMillis() - parsed.time
        return ageMs in -MAX_FUTURE_SKEW_MS..MAX_AGE_MS
    }
}

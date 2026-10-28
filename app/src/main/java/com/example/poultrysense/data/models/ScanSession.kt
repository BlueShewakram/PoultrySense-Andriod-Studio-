package com.example.poultrysense.data.models

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-chicken detail recorded during a checkup session.
 */
data class ChickenSessionDetail(
    val chickenId: Int = 0,
    val anomalyScore: Int = 0,
    val anomalyTier: String = "Normal",
    val behavior: String = "stand",
    val geminiSummary: String = ""
)

/**
 * A completed AI checkup scan session with per-chicken breakdown and barn location.
 */
data class ScanSession(
    val id: String,
    val sessionNumber: String,
    val date: String,
    val time: String,
    val status: String,
    val totalFlock: Int,
    val healthyCount: Int,
    val illCount: Int,
    val healthIndex: String,
    val barnId: String = "",
    val barnName: String = "",
    val zoneName: String = "",
    val chickenDetails: List<ChickenSessionDetail> = emptyList(),
    /** Epoch millis when the scan was saved. 0 for sessions recorded by older builds. */
    val timestamp: Long = 0L
) {
    val barnDisplayName: String
        get() = listOf(barnName, zoneName)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

    /** Full timestamp with seconds, falling back to the legacy date/time strings. */
    val loggedAt: String
        get() = if (timestamp > 0L) {
            SimpleDateFormat("MMM d, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date(timestamp))
        } else {
            listOf(date, time).filter { it.isNotBlank() }.joinToString(" • ")
        }
}

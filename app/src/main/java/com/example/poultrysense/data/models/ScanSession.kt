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
    val anomalyTier: String? = "Normal",
    val behavior: String? = "stand",
    val geminiSummary: String? = ""
)

/**
 * A completed AI checkup scan session with per-chicken breakdown and barn location.
 */
data class ScanSession(
    val id: String = "",
    val sessionNumber: String = "",
    val date: String = "",
    val time: String = "",
    val status: String = "",
    val totalFlock: Int = 0,
    val healthyCount: Int = 0,
    val illCount: Int = 0,
    val healthIndex: String = "--",
    val barnId: String = "",
    val barnName: String = "",
    val zoneName: String = "",
    val chickenDetails: List<ChickenSessionDetail>? = emptyList(),
    /** Epoch millis when the scan was saved. 0 for sessions recorded by older builds. */
    val timestamp: Long = 0L,
    /** True once the farmer has acknowledged/reviewed this scan (set on tap). */
    val reviewed: Boolean = false
) {
    val safeChickenDetails: List<ChickenSessionDetail>
        get() = chickenDetails ?: emptyList()

    val barnDisplayName: String
        get() = listOfNotNull(barnName, zoneName)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifEmpty { "Main Barn • Zone 1" }

    /** Full timestamp with seconds, falling back to the legacy date/time strings. */
    val loggedAt: String
        get() = if (timestamp > 0L) {
            SimpleDateFormat("MMM d, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date(timestamp))
        } else {
            listOfNotNull(date, time).filter { it.isNotBlank() }.joinToString(" • ")
        }
}

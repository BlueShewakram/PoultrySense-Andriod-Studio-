package com.example.poultrysense.data.models

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
    val barnId: String = "barn_alpha_zone_1",
    val barnName: String = "Barn Alpha",
    val zoneName: String = "Zone 1 (Main Pen)",
    val chickenDetails: List<ChickenSessionDetail> = emptyList()
) {
    val barnDisplayName: String
        get() = "$barnName • $zoneName"
}

package com.example.poultrysense.data.models

/**
 * Single source of truth for the four anomaly tiers shown to farmers.
 *
 * The server sends the internal tier names ("Normal", "Monitor", "Suspicious",
 * "High Concern"). Every UI surface maps them through here so a bird reads the
 * same word everywhere. Video badges carry the tier in their colour and show a
 * factual reason instead of the tier name.
 */
object TierLabels {
    const val NORMAL = "Normal"
    const val WATCH = "Watch"
    const val ATTENTION = "Attention"
    const val ALERT = "Alert"

    fun forTier(tier: String?): String = when (tier?.trim()) {
        "Monitor" -> WATCH
        "Suspicious" -> ATTENTION
        "High Concern" -> ALERT
        else -> NORMAL
    }
}

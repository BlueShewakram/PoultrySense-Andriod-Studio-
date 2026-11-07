package com.example.poultrysense.data.models

enum class AlertSeverity {
    CRITICAL,
    WARNING,
    INFO
}

data class FarmAlert(
    val id: String,
    val severity: AlertSeverity,
    val title: String,
    val description: String,
    val timeAgo: String,
    val timestamp: String,
    var read: Boolean = false,
    var isResolved: Boolean = false,
    val chickenId: String? = null,
    val location: String? = null,
    val tagColorName: String? = null,
    val tagColorHex: String? = null,
    val tagNumber: Int? = null,
    /** True for alerts reconstructed from a completed scan (read-only history). */
    val isHistorical: Boolean = false,
    /** Completed scan this alert belongs to, if any. */
    val scanId: String? = null
)

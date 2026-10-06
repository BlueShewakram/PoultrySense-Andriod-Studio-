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
    val chickenId: String? = null,
    val location: String? = null,
    val tagColorName: String? = null,
    val tagColorHex: String? = null,
    val tagNumber: Int? = null
)

package com.example.poultrysense.data.models

/**
 * Represents a physical or operational poultry barn and pen zone.
 * Allows independent assignment of Camera and IoT Climate Sensors.
 */
data class BarnZone(
    val id: String,
    val barnName: String,               // e.g. "Barn Alpha", "Barn Beta"
    val zoneName: String,               // e.g. "Zone 1 (Main Pen)", "Zone 2 (Brooder)"
    val totalCapacity: Int,             // e.g. 50 birds, 100 birds
    val hasLiveHardware: Boolean,       // Master hardware state
    val hasCamera: Boolean = hasLiveHardware,   // true if physical camera assigned
    val hasSensor: Boolean = hasLiveHardware,   // true if physical ESP32 sensor assigned
    val cameraName: String = if (hasCamera) "Cam 1 (Live AI Vision)" else "No Camera Assigned",
    val sensorName: String = if (hasSensor) "ESP32 #1 (DHT22 Live)" else "No Sensor Assigned",
    val cameraChannelId: Int = if (hasCamera) 1 else 0,
    val firebaseTelemetryPath: String = "sensor_data/live",
    val description: String = "Broiler Enclosure",
    val isCustom: Boolean = false       // true if user-created
) {
    val fullDisplayName: String
        get() = "$barnName • $zoneName"

    val isFullyOnline: Boolean
        get() = hasCamera && hasSensor
}

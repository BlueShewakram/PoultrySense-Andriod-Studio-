package com.example.poultrysense.data.models

import com.google.gson.annotations.SerializedName

data class ChickenAlertItem(
    @SerializedName("chicken_id") val chickenId: Int? = 0,
    @SerializedName("status") val status: String? = "Needs Attention",
    @SerializedName("anomaly_score") val anomalyScore: Int? = 0,
    @SerializedName("behavior") val behavior: String? = "",
    @SerializedName("duration_sec") val durationSec: Double? = 0.0,
    @SerializedName("severity") val severity: String? = "Moderate",
    @SerializedName("reason") val reason: String? = "",
    @SerializedName("recommendation") val recommendation: String? = "",
    @SerializedName("gemini_evaluated") val geminiEvaluated: Boolean? = false,
    @SerializedName("timestamp") val timestamp: String? = ""
)

data class DetectionItem(
    @SerializedName("track_id") val trackId: Int? = 0,
    @SerializedName("class") val className: String? = "chicken",
    @SerializedName("confidence") val confidence: Double? = 0.0,
    @SerializedName("behavior") val behavior: String? = "",
    @SerializedName("health") val health: String? = "Healthy",
    @SerializedName("health_detail") val healthDetail: String? = "",
    @SerializedName("anomaly_score") val anomalyScore: Int? = 0,
    @SerializedName("anomaly_tier") val anomalyTier: String? = "Normal",
    @SerializedName("gemini_summary") val geminiSummary: String? = "",
    @SerializedName("gemini_verified") val geminiVerified: Boolean? = false,
    @SerializedName("reason") val reason: String? = "",
    @SerializedName("isolated") val isolated: Boolean? = false,
    @SerializedName("motion") val motion: Double? = 0.0,
    @SerializedName("observed_sec") val observedSec: Double? = 0.0,
    @SerializedName("stationary_sec") val stationarySec: Double? = 0.0,
    @SerializedName("bbox") val bbox: List<Int>? = emptyList()
)

data class AiStatusResponse(
    @SerializedName("active") val active: Boolean? = false,
    @SerializedName("fps") val fps: Double? = 0.0,
    @SerializedName("stream_fps") val streamFps: Double? = 0.0,
    @SerializedName("detection_fps") val detectionFps: Double? = 0.0,
    @SerializedName("detection_conf") val detectionConf: Double? = 0.0,
    @SerializedName("frame_width") val frameWidth: Int? = 640,
    @SerializedName("frame_height") val frameHeight: Int? = 480,
    @SerializedName("detection_frame_generation") val detectionFrameGeneration: Long? = 0,
    @SerializedName("flock_count") val flockCount: Int? = 0,
    @SerializedName("person_count") val personCount: Int? = 0,
    @SerializedName("hand_count") val handCount: Int? = 0,
    @SerializedName("observing_count") val observingCount: Int? = 0,
    @SerializedName("healthy_count") val healthyCount: Int? = 0,
    @SerializedName("warning_count") val warningCount: Int? = 0,
    @SerializedName("unhealthy_count") val unhealthyCount: Int? = 0,
    @SerializedName("isolated_count") val isolatedCount: Int? = 0,
    @SerializedName("health_index") val healthIndex: Int? = 100,
    @SerializedName("track_count") val trackCount: Int? = 0,
    @SerializedName("temperature") val temperature: Double? = null,
    @SerializedName("humidity") val humidity: Double? = null,
    @SerializedName("sensor_connected") val sensorConnected: Boolean? = false,
    @SerializedName("detections") val detections: List<DetectionItem>? = emptyList(),
    @SerializedName("alerts") val alerts: List<ChickenAlertItem>? = emptyList()
)

data class DetectionOverlayResponse(
    @SerializedName("frame_generation") val frameGeneration: Long? = 0,
    @SerializedName("updated_at") val updatedAt: Double? = 0.0,
    @SerializedName("frame_width") val frameWidth: Int? = 640,
    @SerializedName("frame_height") val frameHeight: Int? = 480,
    @SerializedName("detection_fps") val detectionFps: Double? = 0.0,
    @SerializedName("detections") val detections: List<DetectionItem>? = emptyList()
)

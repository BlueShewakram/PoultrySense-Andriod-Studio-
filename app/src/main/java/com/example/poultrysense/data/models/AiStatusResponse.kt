package com.example.poultrysense.data.models

import com.google.gson.annotations.SerializedName

data class AiStatusResponse(
    @SerializedName("active") val active: Boolean? = false,
    @SerializedName("hand_count") val handCount: Int? = 0,
    @SerializedName("flock_count") val flockCount: Int? = 0,
    @SerializedName("health_index") val healthIndex: Int? = 100,
    @SerializedName("healthy_count") val healthyCount: Int? = 0,
    @SerializedName("warning_count") val warningCount: Int? = 0,
    @SerializedName("unhealthy_count") val unhealthyCount: Int? = 0,
    @SerializedName("isolated_count") val isolatedCount: Int? = 0,
    @SerializedName("fps") val fps: Double? = 0.0,
    @SerializedName("temperature") val temperature: Double? = 24.5,
    @SerializedName("humidity") val humidity: Double? = 62.0,
    @SerializedName("detections") val detections: List<Any>? = emptyList()
)

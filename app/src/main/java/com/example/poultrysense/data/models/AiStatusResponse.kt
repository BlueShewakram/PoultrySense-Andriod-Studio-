package com.example.poultrysense.data.models

import com.google.gson.annotations.SerializedName

data class AiStatusResponse(
    @SerializedName("active") val active: Boolean? = false,
    @SerializedName("source") val source: String? = null,
    @SerializedName("person_count") val personCount: Int? = 0,
    @SerializedName("hand_count") val handCount: Int? = 0,
    @SerializedName("fps") val fps: Double? = 0.0,
    @SerializedName("detections") val detections: List<Any>? = emptyList()
)

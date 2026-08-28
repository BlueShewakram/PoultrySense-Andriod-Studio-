package com.example.poultrysense.data.models

data class ScanSession(
    val id: String,
    val sessionNumber: String,
    val date: String,
    val time: String,
    val status: String,
    val totalFlock: Int,
    val healthyCount: Int,
    val illCount: Int,
    val healthIndex: String
)

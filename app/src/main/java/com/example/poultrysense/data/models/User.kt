package com.example.poultrysense.data.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class User(
    val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val farmName: String = "",
    val role: String = "Farm Specialist",
    val avatarUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val scansCount: Int = 0,
    val activeDays: Int = 1
) {
    val fullName: String
        get() = when {
            firstName.isNotBlank() && lastName.isNotBlank() -> "$firstName $lastName"
            firstName.isNotBlank() -> firstName
            lastName.isNotBlank() -> lastName
            else -> "PoultrySense User"
        }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "uid" to uid,
            "firstName" to firstName,
            "lastName" to lastName,
            "fullName" to fullName,
            "email" to email,
            "farmName" to farmName,
            "role" to role,
            "avatarUrl" to avatarUrl,
            "createdAt" to createdAt,
            "scansCount" to scansCount,
            "activeDays" to activeDays
        )
    }
}

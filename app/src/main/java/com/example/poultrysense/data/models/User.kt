package com.example.poultrysense.data.models

import com.google.firebase.database.IgnoreExtraProperties
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@IgnoreExtraProperties
data class User(
    val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val farmName: String = "",
    val role: String = "Lead Farm Specialist",
    val avatarUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
    val createdMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1,
    val createdMonthName: String = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date()),
    val createdYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val formattedDate: String = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date()),
    val scansCount: Int = 0,
    val activeDays: Int = 1
) {
    val fullName: String
        get() = when {
            firstName.isNotBlank() && lastName.isNotBlank() -> "$firstName $lastName"
            firstName.isNotBlank() -> firstName
            lastName.isNotBlank() -> lastName
            else -> "PoultrySense Specialist"
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
            "createdDay" to createdDay,
            "createdMonth" to createdMonth,
            "createdMonthName" to createdMonthName,
            "createdYear" to createdYear,
            "formattedDate" to formattedDate,
            "day" to createdDay,
            "month" to createdMonth,
            "year" to createdYear,
            "date" to formattedDate,
            "scansCount" to scansCount,
            "activeDays" to activeDays
        )
    }
}

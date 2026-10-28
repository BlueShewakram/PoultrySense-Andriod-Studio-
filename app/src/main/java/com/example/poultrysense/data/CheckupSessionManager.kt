package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.data.models.ScanSession
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object CheckupSessionManager {

    private const val PREFS_NAME = "poultrysense_checkup_prefs"
    private const val KEY_IS_ACTIVE = "key_is_active"
    private const val KEY_START_TIME = "key_start_time"
    private const val KEY_DURATION_MINS = "key_duration_mins"
    private const val KEY_ACTIVE_BARN_ID = "key_active_barn_id"
    private const val KEY_ACTIVE_BARN_NAME = "key_active_barn_name"
    private const val KEY_ACTIVE_ZONE_NAME = "key_active_zone_name"
    private const val KEY_SCHEDULE_ENABLED = "key_schedule_enabled"
    private const val KEY_SCHEDULE_HOUR = "key_schedule_hour"
    private const val KEY_SCHEDULE_MINUTE = "key_schedule_minute"
    private const val KEY_SCHEDULE_DURATION = "key_schedule_duration"
    private const val KEY_SCHEDULE_REPEAT = "key_schedule_repeat"
    private const val KEY_SCHEDULE_DATE_MILLIS = "key_schedule_date_millis"
    private const val KEY_SESSION_HISTORY = "key_session_history"
    private const val KEY_SESSION_COUNTER = "key_session_counter"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Live Session State ───

    fun isSessionActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val active = prefs.getBoolean(KEY_IS_ACTIVE, false)
        if (!active) return false

        // Check if duration expired (0 means continuous)
        val duration = prefs.getInt(KEY_DURATION_MINS, 30)
        if (duration > 0) {
            val startTime = prefs.getLong(KEY_START_TIME, 0L)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000L
            if (elapsed >= duration * 60L) {
                stopSession(context)
                return false
            }
        }
        return true
    }

    fun startSession(
        context: Context,
        durationMinutes: Int = 30,
        barnZone: BarnZone? = null
    ) {
        val targetZone = barnZone ?: FarmZoneManager.getActiveZone(context)
        getPrefs(context).edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_DURATION_MINS, durationMinutes)
            .putString(KEY_ACTIVE_BARN_ID, targetZone.id)
            .putString(KEY_ACTIVE_BARN_NAME, targetZone.barnName)
            .putString(KEY_ACTIVE_ZONE_NAME, targetZone.zoneName)
            .apply()
    }

    fun stopSession(
        context: Context,
        totalFlock: Int = 0,
        healthyCount: Int = 0,
        illCount: Int = 0,
        barnZone: BarnZone? = null
    ) {
        val prefs = getPrefs(context)
        val wasActive = prefs.getBoolean(KEY_IS_ACTIVE, false)

        val fallbackActive = FarmZoneManager.getActiveZone(context)
        val barnId = barnZone?.id ?: prefs.getString(KEY_ACTIVE_BARN_ID, fallbackActive.id) ?: fallbackActive.id
        val barnName = barnZone?.barnName ?: prefs.getString(KEY_ACTIVE_BARN_NAME, fallbackActive.barnName) ?: fallbackActive.barnName
        val zoneName = barnZone?.zoneName ?: prefs.getString(KEY_ACTIVE_ZONE_NAME, fallbackActive.zoneName) ?: fallbackActive.zoneName

        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, false)
            .putLong(KEY_START_TIME, 0L)
            .apply()

        if (wasActive) {
            recordCompletedSession(
                context = context,
                totalFlock = totalFlock,
                healthyCount = healthyCount,
                illCount = illCount,
                barnId = barnId,
                barnName = barnName,
                zoneName = zoneName
            )
        }
    }

    fun recordCompletedSession(
        context: Context,
        totalFlock: Int = 0,
        healthyCount: Int = 0,
        illCount: Int = 0,
        barnId: String = "barn_alpha_zone_1",
        barnName: String = "Barn Alpha",
        zoneName: String = "Zone 1 (Main Pen)"
    ) {
        val prefs = getPrefs(context)
        val counter = prefs.getInt(KEY_SESSION_COUNTER, 108) + 1
        prefs.edit().putInt(KEY_SESSION_COUNTER, counter).apply()

        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)

        val flock = if (totalFlock > 0) totalFlock else 0
        val healthy = if (healthyCount > 0) healthyCount else (flock - illCount).coerceAtLeast(0)
        val ill = if (illCount >= 0) illCount else (flock - healthy).coerceAtLeast(0)

        val healthIndex = String.format(Locale.getDefault(), "%.1f%%", if (flock > 0) (healthy.toFloat() / flock.toFloat()) * 100f else 100f)

        val newSession = ScanSession(
            id = "session-$counter",
            sessionNumber = "Scan #$counter",
            date = dateStr,
            time = timeStr,
            status = "Completed",
            totalFlock = flock,
            healthyCount = healthy,
            illCount = ill,
            healthIndex = healthIndex,
            barnId = barnId,
            barnName = barnName,
            zoneName = zoneName
        )

        val currentList = getCompletedSessions(context).toMutableList()
        currentList.add(0, newSession)

        val trimmed = if (currentList.size > 30) currentList.subList(0, 30) else currentList
        val json = Gson().toJson(trimmed)
        prefs.edit().putString(KEY_SESSION_HISTORY, json).apply()

        // Sync to Firebase Cloud Realtime Database: /farms/scan_history/session-X
        try {
            val db = FirebaseDatabase.getInstance().getReference("farms").child("scan_history")
            db.child("session-$counter").setValue(newSession)
        } catch (_: Exception) {}
    }

    fun getCompletedSessions(context: Context): List<ScanSession> {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_SESSION_HISTORY, null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<ScanSession>>() {}.type
                val list: List<ScanSession>? = Gson().fromJson(json, type)
                if (!list.isNullOrEmpty()) {
                    return list
                }
            } catch (_: Exception) {}
        }

        return listOf(
            ScanSession(
                id = "session-108",
                sessionNumber = "Scan #108",
                date = "Sep 13, 2026",
                time = "10:30 AM",
                status = "Completed",
                totalFlock = 350,
                healthyCount = 346,
                illCount = 4,
                healthIndex = "98.8%",
                barnId = "barn_alpha_zone_1",
                barnName = "Barn Alpha",
                zoneName = "Zone 1 (Main Pen)"
            ),
            ScanSession(
                id = "session-107",
                sessionNumber = "Scan #107",
                date = "Sep 12, 2026",
                time = "05:00 PM",
                status = "Completed",
                totalFlock = 350,
                healthyCount = 345,
                illCount = 5,
                healthIndex = "98.5%",
                barnId = "barn_alpha_zone_1",
                barnName = "Barn Alpha",
                zoneName = "Zone 1 (Main Pen)"
            ),
            ScanSession(
                id = "session-106",
                sessionNumber = "Scan #106",
                date = "Sep 12, 2026",
                time = "09:00 AM",
                status = "Completed",
                totalFlock = 350,
                healthyCount = 348,
                illCount = 2,
                healthIndex = "99.4%",
                barnId = "barn_beta_zone_1",
                barnName = "Barn Beta",
                zoneName = "Zone 1 (Layer Paddock)"
            )
        )
    }

    fun getSessionDurationMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_DURATION_MINS, 30)
    }

    fun getRemainingSeconds(context: Context): Long {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) return 0L
        val duration = prefs.getInt(KEY_DURATION_MINS, 30)
        if (duration == 0) return -1L // Continuous
        val startTime = prefs.getLong(KEY_START_TIME, 0L)
        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000L
        val totalSec = duration * 60L
        val remaining = totalSec - elapsedSec
        return if (remaining > 0) remaining else 0L
    }

    fun getElapsedSeconds(context: Context): Long {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) return 0L
        val startTime = prefs.getLong(KEY_START_TIME, 0L)
        return (System.currentTimeMillis() - startTime) / 1000L
    }

    // ─── Scheduling Preferences ───

    fun isScheduleEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCHEDULE_ENABLED, true)
    }

    fun setScheduleEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    fun saveSchedule(
        context: Context,
        hour: Int,
        minute: Int,
        durationMinutes: Int,
        repeatMode: String,
        enabled: Boolean = true,
        dateMillis: Long = System.currentTimeMillis()
    ) {
        getPrefs(context).edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, enabled)
            .putInt(KEY_SCHEDULE_HOUR, hour)
            .putInt(KEY_SCHEDULE_MINUTE, minute)
            .putInt(KEY_SCHEDULE_DURATION, durationMinutes)
            .putString(KEY_SCHEDULE_REPEAT, repeatMode)
            .putLong(KEY_SCHEDULE_DATE_MILLIS, dateMillis)
            .apply()
    }

    fun getScheduledHour(context: Context): Int = getPrefs(context).getInt(KEY_SCHEDULE_HOUR, 8)
    fun getScheduledMinute(context: Context): Int = getPrefs(context).getInt(KEY_SCHEDULE_MINUTE, 0)
    fun getScheduledDuration(context: Context): Int = getPrefs(context).getInt(KEY_SCHEDULE_DURATION, 30)
    fun getScheduledRepeat(context: Context): String = getPrefs(context).getString(KEY_SCHEDULE_REPEAT, "Daily") ?: "Daily"
    fun getScheduledDateMillis(context: Context): Long = getPrefs(context).getLong(KEY_SCHEDULE_DATE_MILLIS, System.currentTimeMillis())

    fun getScheduleDescription(context: Context): String {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_SCHEDULE_ENABLED, true)) {
            return "Automated checkups paused"
        }
        val hour = prefs.getInt(KEY_SCHEDULE_HOUR, 8)
        val minute = prefs.getInt(KEY_SCHEDULE_MINUTE, 0)
        val duration = prefs.getInt(KEY_SCHEDULE_DURATION, 30)
        val repeat = prefs.getString(KEY_SCHEDULE_REPEAT, "Daily") ?: "Daily"

        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        val timeStr = String.format(Locale.getDefault(), "%d:%02d %s", displayHour, minute, amPm)
        val durStr = if (duration == 0) "Continuous" else "$duration mins"

        return "$repeat at $timeStr ($durStr)"
    }
}

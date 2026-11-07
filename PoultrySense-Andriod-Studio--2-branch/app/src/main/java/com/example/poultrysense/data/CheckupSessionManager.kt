package com.example.poultrysense.data

import android.content.Context
import android.content.SharedPreferences
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.data.models.ChickenSessionDetail
import com.example.poultrysense.data.models.ScanSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

object CheckupSessionManager {

    private val scanHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Fire-and-forget scan lifecycle ping. Signals the server to reset all Gemini
     * alert cooldowns and begin the observation epoch when a scan starts.
     */
    fun notifyScanEvent(context: Context, path: String, payload: String? = null) {
        try {
            val baseUrl = ServerPreferences.getBaseUrl(context)
            if (baseUrl.isBlank()) return
            val body = (payload ?: "{}")
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$baseUrl$path").post(body).build()
            scanHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (_: Exception) {}
    }

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

    val activeChickenMap = mutableMapOf<Int, ChickenSessionDetail>()

    fun recordSessionDetection(detail: ChickenSessionDetail) {
        val existing = activeChickenMap[detail.chickenId]
        val score = detail.anomalyScore
        val tier = detail.anomalyTier.orEmpty()
        val summary = detail.geminiSummary.orEmpty()
        if (existing == null || score >= existing.anomalyScore || summary.isNotBlank()) {
            activeChickenMap[detail.chickenId] = ChickenSessionDetail(
                chickenId = detail.chickenId,
                anomalyScore = maxOf(score, existing?.anomalyScore ?: 0),
                anomalyTier = if (tier == "High Concern" || existing?.anomalyTier == "High Concern") "High Concern"
                              else if (tier == "Suspicious" || existing?.anomalyTier == "Suspicious") "Suspicious"
                              else tier,
                behavior = if (!detail.behavior.isNullOrBlank()) detail.behavior else existing?.behavior ?: "stand",
                geminiSummary = if (summary.isNotBlank()) summary else existing?.geminiSummary.orEmpty()
            )
        }
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
        activeChickenMap.clear()
        val targetZone = barnZone ?: FarmZoneManager.getActiveZone(context)
        getPrefs(context).edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_DURATION_MINS, durationMinutes)
            .putString(KEY_ACTIVE_BARN_ID, targetZone.id)
            .putString(KEY_ACTIVE_BARN_NAME, targetZone.barnName)
            .putString(KEY_ACTIVE_ZONE_NAME, targetZone.zoneName)
            .apply()
        notifyScanEvent(context, "/scan/start", null)
    }

    fun stopSession(
        context: Context,
        totalFlock: Int = 0,
        healthyCount: Int = 0,
        illCount: Int = 0,
        barnZone: BarnZone? = null,
        chickenDetails: List<ChickenSessionDetail> = emptyList()
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
            val payload = """{"barn_name":"$barnName"}"""
            notifyScanEvent(context, "/scan/stop", payload)

            val effectiveDetails = if (chickenDetails.isNotEmpty()) {
                chickenDetails
            } else {
                activeChickenMap.values.toList()
            }
            val effectiveTotal = if (totalFlock > 0) totalFlock else effectiveDetails.size
            val flaggedCount = effectiveDetails.count {
                val t = it.anomalyTier.orEmpty()
                (t.isNotBlank() && t != "Normal") || it.anomalyScore >= 40
            }
            val effectiveIll = if (illCount > 0) illCount else flaggedCount
            val effectiveHealthy = if (healthyCount > 0) healthyCount else (effectiveTotal - effectiveIll).coerceAtLeast(0)

            recordCompletedSession(
                context = context,
                totalFlock = effectiveTotal,
                healthyCount = effectiveHealthy,
                illCount = effectiveIll,
                barnId = barnId,
                barnName = barnName,
                zoneName = zoneName,
                chickenDetails = effectiveDetails
            )
            activeChickenMap.clear()
        }
    }

    fun recordCompletedSession(
        context: Context,
        totalFlock: Int = 0,
        healthyCount: Int = 0,
        illCount: Int = 0,
        barnId: String = "",
        barnName: String = "",
        zoneName: String = "",
        chickenDetails: List<ChickenSessionDetail> = emptyList()
    ) {
        val activeZone = FarmZoneManager.getActiveZone(context)
        val effectiveBarnId = barnId.ifBlank { activeZone.id }
        val effectiveBarnName = barnName.ifBlank { activeZone.barnName }
        val effectiveZoneName = zoneName.ifBlank { activeZone.zoneName }

        val prefs = getPrefs(context)
        val counter = prefs.getInt(KEY_SESSION_COUNTER, 0) + 1
        prefs.edit().putInt(KEY_SESSION_COUNTER, counter).apply()

        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)

        val flock = if (totalFlock > 0) totalFlock else 0
        val healthy = if (healthyCount > 0) healthyCount else (flock - illCount).coerceAtLeast(0)
        val ill = if (illCount >= 0) illCount else (flock - healthy).coerceAtLeast(0)

        val healthIndex = if (flock > 0) {
            String.format(Locale.getDefault(), "%.1f%%", (healthy.toFloat() / flock.toFloat()) * 100f)
        } else {
            "--"
        }

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
            barnId = effectiveBarnId,
            barnName = effectiveBarnName,
            zoneName = effectiveZoneName,
            chickenDetails = chickenDetails,
            timestamp = cal.timeInMillis
        )

        val currentList = getCompletedSessions(context).toMutableList()
        currentList.add(0, newSession)

        val trimmed = if (currentList.size > 30) currentList.subList(0, 30) else currentList
        val json = Gson().toJson(trimmed)
        prefs.edit().putString(KEY_SESSION_HISTORY, json).apply()

        // Sync to the signed-in farmer's cloud history and shared farm history
        // so it can be restored on another device or reinstall.
        try {
            val uid = UserPreferences.getCachedUser(context)?.uid
            val db = FirebaseConfig.database()
            if (!uid.isNullOrBlank()) {
                db.getReference("users").child(uid).child("scan_history")
                    .child("session-$counter").setValue(newSession)
            }
            db.getReference("farms").child("scan_history")
                .child("session-$counter").setValue(newSession)
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
                    // One-time cleanup of demo scans and old "Barn Alpha/Beta" labels.
                    val cleaned = list
                        .filterNot { isLegacyDemoSession(it) }
                        .map { normalizeLegacySession(context, it) }
                    if (cleaned != list) {
                        prefs.edit()
                            .putString(KEY_SESSION_HISTORY, Gson().toJson(cleaned))
                            .apply()
                    }
                    return cleaned
                }
            } catch (_: Exception) {}
        }

        // No demo sessions: an empty history is the honest state until a real scan runs.
        return emptyList()
    }

    private fun isLegacyDemoSession(session: ScanSession): Boolean {
        if (session.id !in setOf("session-106", "session-107", "session-108")) return false
        return session.date == "Sep 13, 2026" || session.date == "Sep 12, 2026"
    }

    /** Old builds shipped demo barn names; map those records onto the real farm zone. */
    private fun normalizeLegacySession(context: Context, session: ScanSession): ScanSession {
        val legacyIds = setOf("barn_alpha_zone_1", "barn_alpha_zone_2", "barn_beta_zone_1")
        val legacyNames = setOf("Barn Alpha", "Barn Beta")
        if (session.barnId !in legacyIds && session.barnName !in legacyNames) return session

        val zone = FarmZoneManager.getAllZones(context).firstOrNull() ?: return session
        return session.copy(
            barnId = zone.id,
            barnName = zone.barnName,
            zoneName = zone.zoneName
        )
    }

    /**
     * Merges the farmer's cloud scan history into the local cache so Alerts and
     * History show the same completed scans after a reinstall or on another device.
     */
    suspend fun refreshFromCloud(context: Context): List<ScanSession> {
        val local = getCompletedSessions(context)
        val uid = UserPreferences.getCachedUser(context)?.uid

        val cloudSessions = mutableListOf<ScanSession>()

        if (!uid.isNullOrBlank()) {
            val userSessions = fetchSessionsFromRef(FirebaseConfig.database().getReference("users").child(uid).child("scan_history"))
            cloudSessions.addAll(userSessions)
        }

        val farmSessions = fetchSessionsFromRef(FirebaseConfig.database().getReference("farms").child("scan_history"))
        cloudSessions.addAll(farmSessions)

        if (cloudSessions.isEmpty()) return local

        val merged = (cloudSessions + local)
            .filterNot { isLegacyDemoSession(it) }
            .associateBy { it.id }
            .values
            .sortedByDescending { sessionCounter(it) }
            .take(30)

        val prefs = getPrefs(context)
        val highestCounter = merged.maxOfOrNull { sessionCounter(it) }?.toInt() ?: 0
        prefs.edit()
            .putString(KEY_SESSION_HISTORY, Gson().toJson(merged))
            .putInt(KEY_SESSION_COUNTER, maxOf(prefs.getInt(KEY_SESSION_COUNTER, 0), highestCounter))
            .apply()
        return merged
    }

    private suspend fun fetchSessionsFromRef(ref: com.google.firebase.database.DatabaseReference): List<ScanSession> {
        return try {
            withTimeoutOrNull(6_000L) {
                suspendCancellableCoroutine<List<ScanSession>> { cont ->
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val list = snapshot.children.mapNotNull { child ->
                                try {
                                    Gson().fromJson(Gson().toJson(child.value), ScanSession::class.java)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            if (cont.isActive) cont.resume(list)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                    ref.addListenerForSingleValueEvent(listener)
                    cont.invokeOnCancellation { ref.removeEventListener(listener) }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sessionCounter(session: ScanSession): Long {
        return session.sessionNumber.filter { it.isDigit() }.toLongOrNull()
            ?: session.id.filter { it.isDigit() }.toLongOrNull()
            ?: 0L
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
        return getPrefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)
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
        if (!prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)) {
            return "No automated checkup scheduled"
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

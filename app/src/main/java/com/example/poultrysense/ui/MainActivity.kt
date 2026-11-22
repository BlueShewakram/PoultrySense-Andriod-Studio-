package com.example.poultrysense.ui

import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.example.poultrysense.R
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.FirebaseConfig
import com.example.poultrysense.data.SensorTelemetry
import com.example.poultrysense.data.repository.ProfileRepository
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.data.repository.EnvironmentRepository
import com.example.poultrysense.databinding.ActivityMainBinding

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.data.NotificationHelper
import com.example.poultrysense.data.models.TierLabels
import com.example.poultrysense.data.repository.AuthRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import java.util.Locale
import android.os.Build
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val tabOrder = listOf(
        R.id.navigation_home,
        R.id.navigation_monitor,
        R.id.navigation_alerts,
        R.id.navigation_history,
        R.id.navigation_profile
    )
    private var currentTabId = R.id.navigation_home

    private var firebaseListener: ValueEventListener? = null
    private val climateSamples = mutableListOf<ClimateSample>()
    private val realtimeDb by lazy { FirebaseConfig.database() }

    private data class ClimateSample(
        val temperature: Double,
        val humidity: Double
    )

    // Alerts are populated only from live sensor / AI data. No demo rows.
    val farmAlerts = mutableListOf<FarmAlert>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        navController = navHostFragment.navController

        setupAnimatedBottomNavigation()
        setupFirebaseClimateAlertsListener()
        setupFirebaseAiAlertsListener()

        // Notification Bell Click -> Navigate to Alerts tab with spring pop
        binding.btnNotificationBell.setOnClickListener { view ->
            playBellBounce(view) {
                navigateToTab(R.id.navigation_alerts)
            }
        }

        updateNotificationBadge()
        preloadUserProfileAndAvatar()

        // Initialize Android push notification channel and request permission
        NotificationHelper.initNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Navigate to Alerts tab if opened from push notification
        if (intent?.getBooleanExtra("navigate_to_alerts", false) == true) {
            navigateToTab(R.id.navigation_alerts)
        }
    }

    /**
     * Restores the signed-in farmer's profile from the database on every launch.
     * This is what makes name / farm / avatar survive an uninstall + reinstall.
     */
    private fun preloadUserProfileAndAvatar() {
        val authRepo = AuthRepository()
        val currentUser = authRepo.currentUser ?: return
        val profileRepository = ProfileRepository(this)
        lifecycleScope.launch {
            profileRepository.syncPendingProfile()
            profileRepository.refreshProfile(currentUser)
        }
    }

    private fun setupAnimatedBottomNavigation() {
        binding.navView.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) return@setOnItemSelectedListener false

            val currentIndex = tabOrder.indexOf(currentTabId)
            val targetIndex = tabOrder.indexOf(item.itemId)
            val isForward = targetIndex >= currentIndex

            val navOptions = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.mobile_navigation, false)
                .setEnterAnim(if (isForward) R.anim.slide_in_right else R.anim.slide_in_left)
                .setExitAnim(if (isForward) R.anim.slide_out_left else R.anim.slide_out_right)
                .setPopEnterAnim(if (isForward) R.anim.slide_in_left else R.anim.slide_in_right)
                .setPopExitAnim(if (isForward) R.anim.slide_out_right else R.anim.slide_out_left)
                .build()

            try {
                navController.navigate(item.itemId, null, navOptions)
                currentTabId = item.itemId
                animateBottomNavItem(item.itemId)
                true
            } catch (e: Exception) {
                false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id in tabOrder && destination.id != currentTabId) {
                currentTabId = destination.id
                binding.navView.menu.findItem(destination.id)?.isChecked = true
                animateBottomNavItem(destination.id)
            }
        }
    }

    private fun animateBottomNavItem(itemId: Int) {
        val itemView = binding.navView.findViewById<View>(itemId)
        if (itemView != null) {
            itemView.animate().cancel()
            itemView.scaleX = 0.78f
            itemView.scaleY = 0.78f
            itemView.animate()
                .scaleX(1.16f)
                .scaleY(1.16f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .start()
                }
                .start()
        }

        // Tactile spring response on the whole floating dock container
        binding.cardBottomNavContainer.animate().cancel()
        binding.cardBottomNavContainer.scaleX = 0.98f
        binding.cardBottomNavContainer.scaleY = 0.98f
        binding.cardBottomNavContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    private fun playBellBounce(view: View, onEnd: () -> Unit) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(120)
                    .withEndAction {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    private fun setupFirebaseClimateAlertsListener() {
        val liveRef = realtimeDb.getReference("sensor_data").child("live")
        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                // Do not raise climate alerts from stale cached telemetry.
                if (!SensorTelemetry.isFresh(snapshot)) return

                val temp = snapshot.child("temperature").getValue(Double::class.java)
                    ?: snapshot.child("temperature").getValue(Long::class.java)?.toDouble()
                val hum = snapshot.child("humidity").getValue(Double::class.java)
                    ?: snapshot.child("humidity").getValue(Long::class.java)?.toDouble()
                val timestamp = snapshot.child("timestamp").getValue(String::class.java) ?: "Just now"
                val zone = snapshot.child("zone").getValue(String::class.java)
                    ?.takeIf { it.isNotBlank() }
                    ?: FarmZoneManager.getActiveZone(this@MainActivity).fullDisplayName

                if (temp != null && hum != null) {
                    evaluateDht22Alerts(temp, hum, timestamp, zone)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        liveRef.addValueEventListener(firebaseListener!!)
    }

    private fun evaluateDht22Alerts(temp: Double, hum: Double, timestamp: String, zone: String) {
        climateSamples.add(ClimateSample(temp, hum))
        while (climateSamples.size > 10) {
            climateSamples.removeAt(0)
        }

        val sampleCount = climateSamples.size
        val recentFive = climateSamples.takeLast(5)
        val avgTemp = climateSamples.map { it.temperature }.average()
        val avgHum = climateSamples.map { it.humidity }.average()
        val isCalibrating = sampleCount < 5
        val heatCriticalConfirmed = recentFive.count { it.temperature >= EnvironmentRepository.TEMP_CRITICAL_HEAT } >= 3 || avgTemp >= EnvironmentRepository.TEMP_CRITICAL_HEAT
        val heatWarningConfirmed = sampleCount >= 5 && recentFive.count { it.temperature >= EnvironmentRepository.TEMP_HEAT_WATCH } >= 4
        val coldWarningConfirmed = sampleCount >= 5 && recentFive.count { it.temperature <= EnvironmentRepository.TEMP_COLD_WATCH } >= 4
        val humidityCriticalConfirmed = recentFive.count { it.humidity >= EnvironmentRepository.HUM_CRITICAL } >= 3 || avgHum >= EnvironmentRepository.HUM_CRITICAL
        val humidityWarningConfirmed = sampleCount >= 5 && recentFive.count { it.humidity >= EnvironmentRepository.HUM_WATCH } >= 4
        val dryAirConfirmed = sampleCount >= 5 && recentFive.count { it.humidity <= EnvironmentRepository.HUM_DRY_AIR } >= 4
        val newAlerts = mutableListOf<FarmAlert>()

        // 1. High Heat Check (DHT22 Temperature)
        if (isCalibrating) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-climate-calibrating",
                    severity = AlertSeverity.INFO,
                    title = "Climate Sensor Calibrating",
                    description = "Collecting several DHT22 readings before deciding whether temperature or humidity needs farmer attention.",
                    timeAgo = "Live Sensor Link",
                    timestamp = timestamp,
                    read = true,
                    location = zone
                )
            )
        } else if (heatCriticalConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-heat-critical",
                    severity = AlertSeverity.CRITICAL,
                    title = String.format(Locale.getDefault(), "Confirmed Severe Heat: %.1f°C avg", avgTemp),
                    description = "Multiple DHT22 readings confirm dangerous heat. Increase ventilation and cooling immediately, then inspect flock behavior.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        } else if (heatWarningConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-heat-warning",
                    severity = AlertSeverity.WARNING,
                    title = String.format(Locale.getDefault(), "Sustained High Temperature: %.1f°C", avgTemp),
                    description = "Temperature has stayed above the stricter warning level across repeated readings. Improve airflow and monitor hydration.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        } else if (coldWarningConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-cold-warning",
                    severity = AlertSeverity.WARNING,
                    title = String.format(Locale.getDefault(), "Sustained Cold Risk: %.1f°C", avgTemp),
                    description = "Temperature stayed below the safe range across repeated readings. Check heating and inspect young or weak birds.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        }

        // 2. Humidity Check (DHT22 Humidity)
        if (!isCalibrating && humidityCriticalConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-humidity-critical",
                    severity = AlertSeverity.CRITICAL,
                    title = String.format(Locale.getDefault(), "Confirmed Extreme Dampness: %.0f%% avg", avgHum),
                    description = "Repeated DHT22 readings confirm extreme humidity. Increase ventilation and check litter moisture.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        } else if (!isCalibrating && humidityWarningConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-humidity-warning",
                    severity = AlertSeverity.WARNING,
                    title = String.format(Locale.getDefault(), "Sustained High Humidity: %.0f%% avg", avgHum),
                    description = "Humidity stayed high across repeated readings. Check ventilation and bedding before it becomes a litter problem.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        } else if (!isCalibrating && dryAirConfirmed) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-dry-air",
                    severity = AlertSeverity.INFO,
                    title = String.format(Locale.getDefault(), "Sustained Dry Air: %.0f%% avg", avgHum),
                    description = "Humidity stayed low across repeated readings. Monitor dust, drinking water, and bird comfort.",
                    timeAgo = "Live Sensor Alert",
                    timestamp = timestamp,
                    location = zone
                )
            )
        }

        // 3. Normal Comfort Check (if no alert was triggered)
        if (newAlerts.isEmpty()) {
            newAlerts.add(
                FarmAlert(
                    id = "alert-climate-optimal",
                    severity = AlertSeverity.INFO,
                    title = String.format(Locale.getDefault(), "Stable Climate: %.1f°C • %.0f%%", avgTemp, avgHum),
                    description = "Repeated DHT22 readings are inside the accepted range. Temperature and humidity monitoring is stable.",
                    timeAgo = "Live Sensor Link",
                    timestamp = timestamp,
                    read = true,
                    location = zone
                )
            )
        }

        // Replace only environment alerts. Live AI chicken alerts are owned by
        // MonitorFragment and must not be wiped by every sensor reading.
        farmAlerts.removeAll { isClimateAlertId(it.id) }
        farmAlerts.addAll(newAlerts)

        updateNotificationBadge()
    }

    private fun isClimateAlertId(id: String): Boolean {
        return id.startsWith("alert-heat-") ||
            id.startsWith("alert-cold-") ||
            id.startsWith("alert-humidity-") ||
            id.startsWith("alert-dry-air") ||
            id.startsWith("alert-climate-")
    }

    private var aiAlertsListener: ValueEventListener? = null
    private var latestVerdictListener: ValueEventListener? = null
    private val notifiedVerdicts = mutableSetOf<String>()

    private fun setupFirebaseAiAlertsListener() {
        // 1. Live Active Alerts Sync from Edge AI Server
        val alertsRef = realtimeDb.getReference("active_alerts")
        val alertListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentChickenAlertIds = mutableSetOf<String>()

                for (child in snapshot.children) {
                    val verdict = child.value as? Map<*, *> ?: continue
                    val cid = (verdict["chicken_id"] as? Number)?.toInt()
                        ?: verdict["chicken_id"]?.toString()?.toIntOrNull()
                        ?: child.key?.substringAfter("chicken_")?.toIntOrNull()
                        ?: continue

                    val alertId = "alert-ai-chicken-$cid"
                    currentChickenAlertIds.add(alertId)

                    val tier = verdict["anomaly_tier"]?.toString()
                        ?: verdict["tier"]?.toString().orEmpty()
                    val severity = verdict["severity"]?.toString().orEmpty()
                    val summary = verdict["gemini_summary"]?.toString()
                        ?: verdict["reason"]?.toString().orEmpty()
                    val reason = verdict["reason"]?.toString() ?: summary
                    val score = (verdict["anomaly_score"] as? Number)?.toInt()
                        ?: verdict["anomaly_score"]?.toString()?.toIntOrNull()
                        ?: 0

                    val isGemini = verdict["gemini_evaluated"] == true ||
                        verdict["gemini_verified"] == true ||
                        summary.contains("Gemini:", ignoreCase = true) ||
                        reason.contains("Gemini:", ignoreCase = true)

                    val isHealthyText = summary.contains("no signs of illness", ignoreCase = true) ||
                        summary.contains("rather than pathological", ignoreCase = true) ||
                        summary.contains("normal physiological", ignoreCase = true) ||
                        reason.contains("no signs of illness", ignoreCase = true)

                    val isNormal = tier.equals("Normal", ignoreCase = true) ||
                        tier.equals("Healthy", ignoreCase = true) ||
                        severity.equals("Low", ignoreCase = true) ||
                        (isHealthyText && !severity.equals("Critical", ignoreCase = true) && !tier.equals("High Concern", ignoreCase = true))

                    if (isNormal) {
                        farmAlerts.removeAll { it.id == alertId }
                        continue
                    }

                    val critical = (severity.equals("Critical", ignoreCase = true) ||
                        tier.equals("High Concern", ignoreCase = true)) && score >= 80

                    val alertSeverity = when {
                        critical -> AlertSeverity.CRITICAL
                        tier.equals("Suspicious", ignoreCase = true) ||
                            severity.equals("High", ignoreCase = true) ||
                            severity.equals("Medium", ignoreCase = true) ||
                            score >= 40 -> AlertSeverity.WARNING
                        else -> AlertSeverity.INFO
                    }

                    val tagInfo = ChickenTagHelper.getTagInfo(cid)
                    val verdictPrefix = if (isGemini) "✓ Gemini" else "AI"
                    val title = "Chicken #$cid [${tagInfo.colorName} Tag] • ${TierLabels.forTier(tier)}"
                    val desc = if (summary.isNotBlank()) summary else "Abnormal posture / mobility ($score% anomaly score)"
                    val timestamp = verdict["timestamp"]?.toString().takeIf { !it.isNullOrBlank() }
                        ?: java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(java.util.Date())

                    val alert = FarmAlert(
                        id = alertId,
                        severity = alertSeverity,
                        title = title,
                        description = desc,
                        timeAgo = "Live • $verdictPrefix",
                        timestamp = timestamp,
                        read = false,
                        isResolved = false,
                        chickenId = cid.toString(),
                        tagColorName = tagInfo.colorName,
                        tagColorHex = tagInfo.colorHex,
                        tagNumber = tagInfo.tagNumber
                    )

                    addAlert(alert)
                }

                // Clean up any chicken alerts that are no longer active in Firebase
                val removed = farmAlerts.filter { it.id.startsWith("alert-ai-chicken-") && it.id !in currentChickenAlertIds }
                if (removed.isNotEmpty()) {
                    farmAlerts.removeAll(removed)
                    updateNotificationBadge()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        aiAlertsListener = alertListener
        alertsRef.addValueEventListener(alertListener)

        // 2. Real-time Gemini Verdict Notification Trigger
        val latestRef = realtimeDb.getReference("diagnostic_verdicts").child("latest")
        val latestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val verdict = snapshot.value as? Map<*, *> ?: return
                val cid = (verdict["chicken_id"] as? Number)?.toInt()
                    ?: verdict["chicken_id"]?.toString()?.toIntOrNull()
                    ?: return

                val tier = verdict["anomaly_tier"]?.toString()
                    ?: verdict["tier"]?.toString().orEmpty()
                val severity = verdict["severity"]?.toString().orEmpty()
                val summary = verdict["gemini_summary"]?.toString()
                    ?: verdict["reason"]?.toString().orEmpty()

                val isNormal = tier.equals("Normal", ignoreCase = true) ||
                    tier.equals("Healthy", ignoreCase = true) ||
                    severity.equals("Low", ignoreCase = true)

                if (isNormal) return

                // Do not notify if there is no active monitoring session running
                if (!CheckupSessionManager.isSessionActive(this@MainActivity)) return

                // Stale verdict check: verify the verdict was generated during this active session
                val sessionStartTime = CheckupSessionManager.getSessionStartTime(this@MainActivity)
                val verdictEpochSec = (verdict["timestamp_epoch"] as? Number)?.toDouble()
                if (verdictEpochSec != null && sessionStartTime > 0L) {
                    val verdictMillis = (verdictEpochSec * 1000).toLong()
                    if (verdictMillis < sessionStartTime) {
                        return // Stale verdict from before session started
                    }
                }

                val tagInfo = ChickenTagHelper.getTagInfo(cid)
                val verdictKey = "${cid}_${tier}_${summary.hashCode()}"
                if (!notifiedVerdicts.contains(verdictKey)) {
                    notifiedVerdicts.add(verdictKey)
                    val title = "⚠️ Gemini Verdict: Chicken #$cid [${tagInfo.colorName} Tag] - ${TierLabels.forTier(tier)}"
                    val desc = summary.ifBlank { "Anomaly detected during diagnostic monitoring" }
                    NotificationHelper.sendFlockNotification(this@MainActivity, title, desc, cid)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        latestVerdictListener = latestListener
        latestRef.addValueEventListener(latestListener)
    }

    fun addAlert(alert: FarmAlert) {
        val existingIndex = farmAlerts.indexOfFirst { it.id == alert.id }
        val isNew = existingIndex < 0
        val isUpdatedDesc = !isNew && farmAlerts[existingIndex].description != alert.description

        if (!isNew) {
            farmAlerts[existingIndex] = alert
        } else {
            farmAlerts.add(0, alert)
        }

        // Dispatch immediate system push notification for Critical or Warning alerts ONLY during an active monitoring session!
        if (CheckupSessionManager.isSessionActive(this) && (isNew || isUpdatedDesc) && (alert.severity == AlertSeverity.CRITICAL || alert.severity == AlertSeverity.WARNING) && !alert.read && !alert.isResolved) {
            val verdictKey = "${alert.id}_${alert.description.hashCode()}"
            if (!notifiedVerdicts.contains(verdictKey)) {
                notifiedVerdicts.add(verdictKey)
                NotificationHelper.sendFlockNotification(this, alert.title, alert.description)
            }
        }
        updateNotificationBadge()
    }

    fun resolveAlert(alertId: String) {
        val alert = farmAlerts.find { it.id == alertId }
        if (alert != null) {
            alert.isResolved = true
            alert.read = true
            updateNotificationBadge()
        }
    }

    fun updateNotificationBadge() {
        val unreadCount = farmAlerts.count { !it.read && it.severity != AlertSeverity.INFO }
        val hasUnread = unreadCount > 0
        binding.notificationBadgeDot.visibility = if (hasUnread) View.VISIBLE else View.GONE

        // Update badge on bottom navigation Alerts tab
        try {
            val badge = binding.navView.getOrCreateBadge(R.id.navigation_alerts)
            if (hasUnread) {
                badge.isVisible = true
                badge.number = unreadCount
                badge.backgroundColor = ContextCompat.getColor(this, R.color.critical)
            } else {
                badge.isVisible = false
            }
        } catch (_: Exception) {}
    }

    fun markAllAlertsRead() {
        farmAlerts.forEach { it.read = true }
        updateNotificationBadge()
    }

    fun navigateToTab(destinationId: Int) {
        binding.navView.selectedItemId = destinationId
    }

    override fun onDestroy() {
        super.onDestroy()
        firebaseListener?.let {
            realtimeDb.getReference("sensor_data").child("live").removeEventListener(it)
        }
        aiAlertsListener?.let {
            realtimeDb.getReference("active_alerts").removeEventListener(it)
        }
        latestVerdictListener?.let {
            realtimeDb.getReference("diagnostic_verdicts").child("latest").removeEventListener(it)
        }
    }
}

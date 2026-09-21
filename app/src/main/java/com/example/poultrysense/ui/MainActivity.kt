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
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ActivityMainBinding

import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

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
    private val realtimeDb by lazy {
        FirebaseDatabase.getInstance("https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app")
    }

    private data class ClimateSample(
        val temperature: Double,
        val humidity: Double
    )

    val farmAlerts = mutableListOf(
        FarmAlert(
            id = "alert-initial",
            severity = AlertSeverity.INFO,
            title = "DHT22 Climate Monitor Active",
            description = "Listening for live temperature and humidity readings from ESP32...",
            timeAgo = "Initializing",
            timestamp = "Just now",
            location = "Main Barn"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        navController = navHostFragment.navController

        setupAnimatedBottomNavigation()
        setupFirebaseClimateAlertsListener()

        // Notification Bell Click -> Navigate to Alerts tab with spring pop
        binding.btnNotificationBell.setOnClickListener { view ->
            playBellBounce(view) {
                navigateToTab(R.id.navigation_alerts)
            }
        }

        updateNotificationBadge()
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

                val temp = snapshot.child("temperature").getValue(Double::class.java)
                    ?: snapshot.child("temperature").getValue(Long::class.java)?.toDouble()
                val hum = snapshot.child("humidity").getValue(Double::class.java)
                    ?: snapshot.child("humidity").getValue(Long::class.java)?.toDouble()
                val timestamp = snapshot.child("timestamp").getValue(String::class.java) ?: "Just now"
                val zone = snapshot.child("zone").getValue(String::class.java) ?: "Main Barn • Zone 1"

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
        val heatCriticalConfirmed = recentFive.count { it.temperature >= 36.0 } >= 3 || avgTemp >= 36.0
        val heatWarningConfirmed = sampleCount >= 5 && recentFive.count { it.temperature >= 33.0 } >= 4
        val coldWarningConfirmed = sampleCount >= 5 && recentFive.count { it.temperature <= 17.0 } >= 4
        val humidityCriticalConfirmed = recentFive.count { it.humidity >= 88.0 } >= 3 || avgHum >= 88.0
        val humidityWarningConfirmed = sampleCount >= 5 && recentFive.count { it.humidity >= 82.0 } >= 4
        val dryAirConfirmed = sampleCount >= 5 && recentFive.count { it.humidity <= 35.0 } >= 4
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

        // Update active alerts list
        farmAlerts.clear()
        farmAlerts.addAll(newAlerts)

        updateNotificationBadge()
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
    }
}

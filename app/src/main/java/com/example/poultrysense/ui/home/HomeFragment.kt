package com.example.poultrysense.ui.home

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.databinding.FragmentHomeBinding
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.FirebaseConfig
import com.example.poultrysense.data.SensorTelemetry
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.poultrysense.data.models.AiStatusResponse
import com.google.gson.Gson
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private var liveFlockCount: Int? = null
    private var liveHealthyCount: Int? = null
    private var liveFlaggedCount: Int? = null

    private val gson = Gson()
    private var streamJob: Job? = null
    private var statusJob: Job? = null
    private var firebaseSensorListener: ValueEventListener? = null
    private val realtimeDb by lazy { FirebaseConfig.database() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        FarmZoneManager.addListener(zoneListener)
        setupBarnZoneSelector()
        setupNavigationActions()
        setupCapsuleInteractions()
        setupSwipeRefresh()
        updateAlertsAndMetrics()
        playEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        // Pick up profile changes (e.g. a renamed farm) that alter the starter zone label.
        val currentZone = FarmZoneManager.getActiveZone(requireContext())
        if (currentZone != activeZone) {
            activeZone = currentZone
            applyActiveZone(currentZone)
        }
        updateAlertsAndMetrics()
        startStreamPolling()
        startSensorPolling()
    }

    override fun onPause() {
        super.onPause()
        stopStreamPolling()
        stopSensorPolling()
    }

    private fun playEntranceAnimation() {
        val views = listOf(
            binding.cardHeroVitality,
            binding.cardCapsuleMorning,
            binding.cardCapsuleAi,
            binding.cardCapsuleHealth,
            binding.cardLiveOverview
        )
        views.forEachIndexed { index, v ->
            v.alpha = 0f
            v.translationY = 32f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setStartDelay((index * 40).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private var activeZone: BarnZone? = null
    private val zoneListener: (BarnZone) -> Unit = { zone ->
        activeZone = zone
        applyActiveZone(zone)
    }

    private fun setupBarnZoneSelector() {
        val ctx = context ?: return
        val current = FarmZoneManager.getActiveZone(ctx)
        activeZone = current
        applyActiveZone(current)

        val openSelector: (View) -> Unit = { view ->
            playBounceAnimation(view) {
                BarnZoneSelectionBottomSheet.newInstance()
                    .show(parentFragmentManager, BarnZoneSelectionBottomSheet.TAG)
            }
        }
        binding.btnSwitchBarnZone.setOnClickListener(openSelector)
    }

    private fun applyActiveZone(zone: BarnZone) {
        if (_binding == null) return
        // Never carry one barn's live counts into another barn's view.
        liveFlockCount = null
        liveHealthyCount = null
        liveFlaggedCount = null

        if (zone.hasCamera && zone.cameraChannelId > 0) {
            val cameraIndex = zone.cameraChannelId - 1
            val ctx = context
            if (ctx != null) {
                val baseUrl = ServerPreferences.getBaseUrl(ctx)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/camera/select?camera_index=$cameraIndex")
                            .post(ByteArray(0).toRequestBody(null))
                            .build()
                        httpClient.newCall(request).execute().close()
                    } catch (_: Exception) {}
                }
            }
        }

        binding.tvActiveZoneName.text = zone.barnName
        binding.tvHomeZoneSubtitle.text = "${zone.barnName} • ${zone.zoneName} Monitoring"
        binding.tvBarnCapacity.text = if (zone.totalCapacity > 0) "${zone.totalCapacity}" else "--"

        val flagged = liveFlaggedCount ?: 0
        binding.tvAnomalyCapsuleDesc.text = when {
            !zone.hasCamera -> "No camera\n${zone.barnName}"
            flagged > 0 -> "$flagged flagged\n${zone.barnName}"
            else -> "No anomalies\n${zone.barnName}"
        }
        binding.tvCameraTitle.text = if (zone.hasCamera) {
            "${zone.cameraName} • ${zone.barnName}"
        } else {
            "No camera assigned"
        }
        binding.tvCameraStatus.text = if (zone.hasCamera) {
            "Waiting for live stream"
        } else {
            "Pair a camera in Settings"
        }
        binding.tvFeedBadge.text = if (zone.hasCamera) "CAMERA STREAM" else "STANDBY"
        binding.tvAnomalyCapsuleLabel.text = when {
            !zone.hasCamera -> "STANDBY"
            flagged > 0 -> "FLAGGED"
            else -> "NO ALERTS"
        }
        binding.tvActiveCapsuleLabel.text = if (zone.hasCamera) "MONITOR" else "STANDBY"

        // Camera hardware routing
        if (!zone.hasCamera) {
            stopStreamPolling()
            binding.ivDashboardStream.visibility = View.GONE
            binding.layoutOfflinePlaceholder.visibility = View.VISIBLE
            binding.tvStreamPlaceholderTitle.text = "No Camera Assigned to ${zone.barnName}"
            binding.tvStreamPlaceholderSubtitle.text = "Standby • Switch active zone or pair camera in Settings"
        } else {
            binding.tvStreamPlaceholderTitle.text = "Live Poultry Vision Stream"
            binding.tvStreamPlaceholderSubtitle.text = "Camera assigned • Waiting for live frames"
            startStreamPolling()
        }

        // Sensor hardware routing
        if (!zone.hasSensor) {
            stopSensorPolling()
            updateClimateUI(null, null, "STANDBY • NO SENSOR PAIRED")
            binding.tvThumbnailHumidity.text = "Sensor: Standby"
            binding.tvBarnHumidity.text = "No sensor assigned to ${zone.barnName}"
            binding.tvBarnTemperature.text = "No sensor assigned to ${zone.barnName}"
            binding.tvClimateCapsuleDesc.text = "${zone.barnName} Standby\nNo sensor paired"
            binding.tvClimateCapsuleStatus.text = "STANDBY"
            binding.tvBarnTempStatus.text = "STANDBY"
            binding.tvBarnHumidityStatus.text = "STANDBY"
        } else {
            // Reset stale values from the previous zone until real telemetry arrives.
            updateClimateUI(null, null)
            startSensorPolling()
        }
    }

    private fun animateNormalRing(targetNormal: Int, totalCapacity: Int) {
        val maxCap = if (totalCapacity > 0) totalCapacity else 1
        binding.progressHealthRing.max = maxCap
        val target = targetNormal.coerceIn(0, maxCap)
        val animator = ValueAnimator.ofInt(0, target).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val count = animation.animatedValue as Int
                _binding?.progressHealthRing?.progress = count
                _binding?.tvNormalCount?.text = "$count"
            }
        }
        animator.start()
    }

    private fun updateFlockMetrics(flock: Int, healthy: Int, flagged: Int) {
        if (_binding == null) return
        binding.tvTotalFlockCount.text = "$flock"
        val capacity = activeZone?.totalCapacity ?: 0
        binding.tvBarnCapacity.text = if (capacity > 0) "$capacity" else "--"
        binding.tvFlaggedCount.text = "$flagged Flagged"
        val barn = activeZone?.barnName.orEmpty()
        binding.tvAnomalyCapsuleDesc.text = if (flagged > 0) "$flagged flagged\n$barn" else "No anomalies\n$barn"
        binding.tvActiveCapsuleDesc.text = if (healthy > 0) {
            "$healthy active\nNo anomalies"
        } else {
            "No birds tracked\nWaiting for AI"
        }
        binding.tvAnomalyCapsuleLabel.text = if (flagged > 0) "FLAGGED" else "NO ALERTS"
        binding.tvActiveCapsuleLabel.text = if (healthy > 0) "ACTIVE" else "STANDBY"
        animateNormalRing(healthy, if (flock > 0) flock else 1)
    }

    private fun updateAlertsAndMetrics() {
        val ctx = context ?: return
        val mainActivity = activity as? MainActivity
        val activeAlertsCount = mainActivity?.farmAlerts?.count { !it.read } ?: 0
        binding.tvActiveAlertsCount.text = if (activeAlertsCount == 1) "1 New" else "$activeAlertsCount New"

        val zone = activeZone ?: FarmZoneManager.getActiveZone(ctx)
        val hasCam = zone.hasCamera
        // Only show a scan that actually belongs to this barn. Never borrow another barn's numbers.
        val latestSession = com.example.poultrysense.data.CheckupSessionManager
            .getCompletedSessions(ctx)
            .firstOrNull { it.barnId == zone.id }

        val totalFlock = if (hasCam) (liveFlockCount ?: (latestSession?.totalFlock ?: 0)) else 0
        val healthy = if (hasCam) (liveHealthyCount ?: (latestSession?.healthyCount ?: 0)) else 0
        val flagged = if (hasCam) (liveFlaggedCount ?: (latestSession?.illCount ?: 0)) else 0
        val lastTime = when {
            !hasCam -> "Standby (No Cam)"
            liveFlockCount != null -> "Live Stream"
            latestSession != null -> "${latestSession.date} • ${latestSession.time}"
            else -> "--"
        }

        binding.tvTotalFlockCount.text = if (hasCam && totalFlock > 0) "$totalFlock" else "--"
        val capacity = zone.totalCapacity
        binding.tvBarnCapacity.text = if (capacity > 0) "$capacity" else "--"
        binding.tvFlaggedCount.text = if (hasCam) "$flagged Flagged" else "--"
        binding.tvLastScanTime.text = lastTime
        binding.tvLastScanStatus.text = when {
            liveFlockCount != null -> "Live AI Feed"
            latestSession != null -> "AI Verified"
            else -> "No scan yet"
        }

        binding.tvAnomalyCapsuleDesc.text = when {
            !hasCam -> "Standby\nNo camera"
            flagged > 0 -> "$flagged flagged\n${zone.barnName}"
            else -> "No anomalies\n${zone.barnName}"
        }
        binding.tvActiveCapsuleDesc.text = when {
            !hasCam -> "Standby\nNo active stream"
            healthy > 0 -> "$healthy active\nNo anomalies"
            else -> "No birds tracked\nWaiting for AI"
        }
        binding.tvAnomalyCapsuleLabel.text = when {
            !hasCam -> "STANDBY"
            flagged > 0 -> "FLAGGED"
            else -> "NO ALERTS"
        }
        binding.tvActiveCapsuleLabel.text = when {
            !hasCam -> "STANDBY"
            healthy > 0 -> "ACTIVE"
            else -> "STANDBY"
        }

        animateNormalRing(
            if (hasCam) healthy else 0,
            when {
                hasCam && totalFlock > 0 -> totalFlock
                capacity > 0 -> capacity
                else -> 1
            }
        )
    }


    private fun setupCapsuleInteractions() {
        val mainActivity = activity as? MainActivity

        // Red Anomaly Card -> Navigate to Alerts
        binding.cardCapsuleMorning.setOnClickListener { view ->
            playBounceAnimation(view) {
                mainActivity?.navigateToTab(R.id.navigation_alerts)
            }
        }

        // Green Normal Card -> Navigate to Monitor
        binding.cardCapsuleAi.setOnClickListener { view ->
            playBounceAnimation(view) {
                mainActivity?.navigateToTab(R.id.navigation_monitor)
            }
        }

        // Amber Climate Card -> Open Live Climate & Sensor Telemetry Sheet
        binding.cardCapsuleHealth.setOnClickListener { view ->
            playBounceAnimation(view) {
                showClimateBottomSheet()
            }
        }
    }

    private fun setupNavigationActions() {
        val mainActivity = activity as? MainActivity

        // View Full Feed -> Navigate to Monitor Tab
        binding.btnViewStream.setOnClickListener {
            mainActivity?.navigateToTab(R.id.navigation_monitor)
        }
    }

    private var liveTemp: Double? = null
    private var liveHumidity: Double? = null
    private var activeClimateSheetBinding: com.example.poultrysense.databinding.DialogClimateDetailsBinding? = null

    private fun showClimateBottomSheet() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheetBinding = com.example.poultrysense.databinding.DialogClimateDetailsBinding.inflate(layoutInflater)
        activeClimateSheetBinding = sheetBinding
        dialog.setContentView(sheetBinding.root)
        sheetBinding.tvClimateDialogSubtitle.text = "${activeZone?.fullDisplayName ?: "Active Zone"} Real-time Telemetry"
        updateClimateSheetValues(sheetBinding, liveTemp, liveHumidity)
        sheetBinding.btnDismissClimateSheet.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            activeClimateSheetBinding = null
        }
        dialog.show()
    }

    private fun updateClimateSheetValues(
        sheetBinding: com.example.poultrysense.databinding.DialogClimateDetailsBinding,
        temp: Double?,
        hum: Double?
    ) {
        if (temp != null && hum != null) {
            sheetBinding.tvDialogTemperature.text = String.format(Locale.getDefault(), "%.1f°C", temp)
            sheetBinding.tvDialogHumidity.text = String.format(Locale.getDefault(), "%.1f%%", hum)
            sheetBinding.tvClimateStatusPill.text =
                com.example.poultrysense.data.repository.EnvironmentRepository.getClimateStatus(temp, hum)
        } else {
            sheetBinding.tvDialogTemperature.text = "--.-°C"
            sheetBinding.tvDialogHumidity.text = "--%"
            sheetBinding.tvClimateStatusPill.text = "NO SENSOR DATA"
        }
    }

    private fun playBounceAnimation(view: android.view.View, onEnd: () -> Unit) {
        view.animate()
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(130)
                    .withEndAction { onEnd() }
                    .start()
            }.start()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary, R.color.accent_green)
        binding.swipeRefreshLayout.setOnRefreshListener {
            updateAlertsAndMetrics()
            binding.swipeRefreshLayout.postDelayed({
                binding.swipeRefreshLayout.isRefreshing = false
            }, 800)
        }
    }

    private fun startStreamPolling() {
        stopStreamPolling()
        val serverUrl = ServerPreferences.getBaseUrl(requireContext()) + "/raw_frame.jpg"
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url(serverUrl).header("Cache-Control", "no-cache").build()
            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    binding.ivDashboardStream.setImageBitmap(bitmap)
                                    binding.ivDashboardStream.visibility = View.VISIBLE
                                    binding.layoutOfflinePlaceholder.visibility = View.GONE
                                    binding.tvFeedBadge.text = "LIVE STREAM"
                                    binding.tvCameraStatus.text = "Live Stream • Active"
                                }
                            }
                        }
                    } else {
                        showOfflinePlaceholder()
                    }
                    response.close()
                } catch (e: Exception) {
                    showOfflinePlaceholder()
                }
                delay(60)
            }
        }
    }

    private var lastHomeCloudIpCheck = 0L

    private fun attemptHomeCloudIpRecovery() {
        val ctx = context ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastHomeCloudIpCheck < 8000L) return
        lastHomeCloudIpCheck = now

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val cloudIp = com.example.poultrysense.data.AiServerScanner.fetchCloudServerIp() ?: return@launch
            val currentIp = ServerPreferences.getIp(ctx)
            if (cloudIp.isNotBlank() && cloudIp != currentIp) {
                if (com.example.poultrysense.data.AiServerScanner.verifyServer(cloudIp)) {
                    ServerPreferences.saveIp(ctx, cloudIp)
                    withContext(Dispatchers.Main) {
                        if (_binding != null) {
                            startStreamPolling()
                        }
                    }
                }
            }
        }
    }

    private suspend fun showOfflinePlaceholder() {
        attemptHomeCloudIpRecovery()
        withContext(Dispatchers.Main) {
            if (_binding != null) {
                binding.ivDashboardStream.visibility = View.GONE
                binding.layoutOfflinePlaceholder.visibility = View.VISIBLE
                binding.tvFeedBadge.text = "CAMERA STREAM"
            }
        }
    }

    private fun updateClimateUI(temp: Double?, hum: Double?, statusOverride: String? = null) {
        if (_binding == null) return
        liveTemp = temp
        liveHumidity = hum

        if (temp != null && hum != null) {
            val tempState = com.example.poultrysense.data.repository.EnvironmentRepository.getTemperatureState(temp)
            val humidityState = com.example.poultrysense.data.repository.EnvironmentRepository.getHumidityState(hum)

            // 1. Live Environment Overview Cards
            binding.tvThumbnailHumidity.text = "Humidity: ${hum.toInt()}% ($humidityState)"
            binding.tvBarnHumidity.text = String.format(Locale.getDefault(), "Current level: %.1f%% (%s)", hum, humidityState)
            binding.tvBarnTemperature.text = String.format(Locale.getDefault(), "Current temp: %.1f°C", temp)

            // 2. Climate Capsule Header Card
            binding.tvClimateCapsuleDesc.text = "${hum.toInt()}% Humidity\n${String.format(Locale.getDefault(), "%.1f", temp)}°C temp"
            val climateStatus = statusOverride ?: com.example.poultrysense.data.repository.EnvironmentRepository.getClimateStatus(temp, hum)
            binding.tvClimateCapsuleStatus.text = climateStatus
            binding.tvBarnTempStatus.text = tempState.uppercase(Locale.getDefault())
            binding.tvBarnHumidityStatus.text = humidityState.uppercase(Locale.getDefault())
        } else {
            // ESP32 Standby / Disconnected State
            binding.tvThumbnailHumidity.text = "Sensor: Standby"
            binding.tvBarnHumidity.text = "Waiting for ESP32 connection..."
            binding.tvBarnTemperature.text = "Waiting for ESP32 connection..."
            binding.tvClimateCapsuleDesc.text = "ESP32 Standby\nNo live sensor signal"
            binding.tvClimateCapsuleStatus.text = "STANDBY"
            binding.tvBarnTempStatus.text = "STANDBY"
            binding.tvBarnHumidityStatus.text = "STANDBY"
        }

        // 3. Realtime update for open Climate Sheet if active
        activeClimateSheetBinding?.let { sheet ->
            updateClimateSheetValues(sheet, temp, hum)
        }
    }

    private fun startSensorPolling() {
        stopSensorPolling()

        // 1. Listen directly to Firebase Cloud Realtime Database (/sensor_data/live)
        // This delivers real-time ESP32 data globally with ZERO IP configuration for customers!
        val liveRef = realtimeDb.getReference("sensor_data").child("live")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                // Stale cached telemetry from a powered-off ESP32 is not live data.
                if (!SensorTelemetry.isFresh(snapshot)) {
                    updateClimateUI(null, null)
                    return
                }
                val temp = snapshot.child("temperature").getValue(Double::class.java)
                    ?: snapshot.child("temperature").getValue(Long::class.java)?.toDouble()
                val hum = snapshot.child("humidity").getValue(Double::class.java)
                    ?: snapshot.child("humidity").getValue(Long::class.java)?.toDouble()
                val status = snapshot.child("status").getValue(String::class.java)

                if (temp != null && hum != null) {
                    updateClimateUI(temp, hum, status)
                } else {
                    updateClimateUI(null, null)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        firebaseSensorListener = listener
        liveRef.addValueEventListener(listener)

        // 2. Fallback / Local PC Server polling (if the customer also runs local Python AI)
        val serverUrl = ServerPreferences.getBaseUrl(requireContext()) + "/status"
        statusJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url(serverUrl).build()
            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val status = gson.fromJson(body, AiStatusResponse::class.java)
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    // Only update climate UI if live sensor data is actually present
                                    if (status.temperature != null && status.humidity != null) {
                                        updateClimateUI(status.temperature, status.humidity)
                                    }

                                    // Dynamic flock count from AI status
                                    val count = status.flockCount ?: (status.handCount ?: (status.detections?.size ?: 0))
                                    val healthy = status.healthyCount ?: count
                                    val flagged = (status.warningCount ?: 0) + (status.unhealthyCount ?: 0)
                                    liveFlockCount = count
                                    liveHealthyCount = healthy
                                    liveFlaggedCount = flagged
                                    updateFlockMetrics(count, healthy, flagged)
                                }
                            }
                        }
                    }
                    response.close()
                } catch (_: Exception) {
                }
                delay(1500)
            }
        }
    }

    private fun stopSensorPolling() {
        firebaseSensorListener?.let {
            realtimeDb.getReference("sensor_data").child("live").removeEventListener(it)
            firebaseSensorListener = null
        }
        statusJob?.cancel()
        statusJob = null
    }

    private fun stopStreamPolling() {
        streamJob?.cancel()
        streamJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        FarmZoneManager.removeListener(zoneListener)
        stopStreamPolling()
        stopSensorPolling()
        _binding = null
    }
}

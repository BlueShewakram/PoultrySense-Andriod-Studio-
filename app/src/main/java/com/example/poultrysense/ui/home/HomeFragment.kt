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
import com.example.poultrysense.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.example.poultrysense.data.models.AiStatusResponse
import com.google.gson.Gson
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
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
    private val realtimeDb by lazy {
        FirebaseDatabase.getInstance("https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app")
    }

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

        setupBarnZoneSelector()
        setupNavigationActions()
        setupCapsuleInteractions()
        setupSwipeRefresh()
        updateAlertsAndMetrics()
        playEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
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

    private var currentZoneIndex = 0
    private val barnZones = listOf("Barn Alpha • Zone 1", "Barn Alpha • Zone 2", "Barn Beta • Zone 1")

    private fun setupBarnZoneSelector() {
        binding.tvActiveZoneName.text = "Barn Alpha"

        binding.btnSwitchBarnZone.setOnClickListener { view ->
            playBounceAnimation(view) {
                currentZoneIndex = (currentZoneIndex + 1) % barnZones.size
                val selectedZone = barnZones[currentZoneIndex]
                val shortName = if (currentZoneIndex == 2) "Barn Beta" else "Barn Alpha"
                binding.tvActiveZoneName.text = shortName
                android.widget.Toast.makeText(context, "Switched to $selectedZone", android.widget.Toast.LENGTH_SHORT).show()
            }
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
        binding.tvBarnCapacity.text = "${(flock * 1.25).toInt()}"
        binding.tvFlaggedCount.text = "$flagged Flagged"
        binding.tvAnomalyCapsuleDesc.text = "$flagged flagged\nBarn Alpha"
        binding.tvActiveCapsuleDesc.text = if (healthy > 0) "$healthy active\nAll zones OK" else "0 active\nAll zones OK"
        animateNormalRing(healthy, if (flock > 0) flock else 1)
    }

    private fun updateAlertsAndMetrics() {
        val ctx = context ?: return
        val mainActivity = activity as? MainActivity
        val activeAlertsCount = mainActivity?.farmAlerts?.count { !it.read } ?: 0
        binding.tvActiveAlertsCount.text = if (activeAlertsCount == 1) "1 New" else "$activeAlertsCount New"

        // Dynamic inspection metrics from live AI status or CheckupSessionManager
        val latestSession = com.example.poultrysense.data.CheckupSessionManager.getCompletedSessions(ctx).firstOrNull()
        val totalFlock = liveFlockCount ?: (latestSession?.totalFlock ?: 0)
        val healthy = liveHealthyCount ?: (latestSession?.healthyCount ?: totalFlock)
        val flagged = liveFlaggedCount ?: (latestSession?.illCount ?: 0)
        val lastTime = if (liveFlockCount != null) "Live Stream" else (latestSession?.time ?: "--")

        binding.tvTotalFlockCount.text = if (totalFlock > 0) "$totalFlock" else "--"
        binding.tvBarnCapacity.text = if (totalFlock > 0) "${(totalFlock * 1.25).toInt()}" else "--"
        binding.tvFlaggedCount.text = "$flagged Flagged"
        binding.tvLastScanTime.text = lastTime

        binding.tvAnomalyCapsuleDesc.text = "$flagged flagged\nBarn Alpha"
        binding.tvActiveCapsuleDesc.text = if (healthy > 0) "$healthy active\nAll zones OK" else "0 active\nAll zones OK"

        animateNormalRing(healthy, if (totalFlock > 0) totalFlock else 1)
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

        binding.btnCustomizeStations.setOnClickListener {
            mainActivity?.navigateToTab(R.id.navigation_monitor)
        }
    }

    private fun setupNavigationActions() {
        val mainActivity = activity as? MainActivity

        // View Full Feed -> Navigate to Monitor Tab
        binding.btnViewStream.setOnClickListener {
            mainActivity?.navigateToTab(R.id.navigation_monitor)
        }

        // View Environmental Gauges -> Open Climate Sheet
        binding.btnEnvDetails.setOnClickListener {
            showClimateBottomSheet()
        }
    }

    private var liveTemp: Double = 24.5
    private var liveHumidity: Double = 62.0
    private var activeClimateSheetBinding: com.example.poultrysense.databinding.DialogClimateDetailsBinding? = null

    private fun showClimateBottomSheet() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheetBinding = com.example.poultrysense.databinding.DialogClimateDetailsBinding.inflate(layoutInflater)
        activeClimateSheetBinding = sheetBinding
        dialog.setContentView(sheetBinding.root)
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
        temp: Double,
        hum: Double
    ) {
        sheetBinding.tvDialogTemperature.text = String.format("%.1f°C", temp)
        sheetBinding.tvDialogHumidity.text = String.format("%.1f%%", hum)
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
        val serverUrl = ServerPreferences.getBaseUrl(requireContext()) + "/frame.jpg"
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

    private suspend fun showOfflinePlaceholder() {
        withContext(Dispatchers.Main) {
            if (_binding != null) {
                binding.ivDashboardStream.visibility = View.GONE
                binding.layoutOfflinePlaceholder.visibility = View.VISIBLE
            }
        }
    }

    private fun updateClimateUI(temp: Double, hum: Double, statusOverride: String? = null) {
        if (_binding == null) return
        liveTemp = temp
        liveHumidity = hum
        val tempState = com.example.poultrysense.data.repository.EnvironmentRepository.getTemperatureState(temp)
        val humidityState = com.example.poultrysense.data.repository.EnvironmentRepository.getHumidityState(hum)

        // 1. Live Environment Overview Cards
        binding.tvThumbnailHumidity.text = "Humidity: ${hum.toInt()}% ($humidityState)"
        binding.tvBarnHumidity.text = String.format("Current level: %.1f%% (%s)", hum, humidityState)
        binding.tvBarnTemperature.text = String.format("Current temp: %.1f°C", temp)

        // 2. Climate Capsule Header Card
        binding.tvClimateCapsuleDesc.text = "${hum.toInt()}% Humidity\n${String.format("%.1f", temp)}°C temp"
        val climateStatus = statusOverride ?: com.example.poultrysense.data.repository.EnvironmentRepository.getClimateStatus(temp, hum)
        binding.tvClimateCapsuleStatus.text = climateStatus
        binding.tvBarnTempStatus.text = tempState.uppercase(Locale.getDefault())
        binding.tvBarnHumidityStatus.text = humidityState.uppercase(Locale.getDefault())

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
                if (_binding == null || !snapshot.exists()) return
                val temp = snapshot.child("temperature").getValue(Double::class.java)
                    ?: snapshot.child("temperature").getValue(Long::class.java)?.toDouble()
                val hum = snapshot.child("humidity").getValue(Double::class.java)
                    ?: snapshot.child("humidity").getValue(Long::class.java)?.toDouble()
                val status = snapshot.child("status").getValue(String::class.java)

                if (temp != null && hum != null) {
                    updateClimateUI(temp, hum, status)
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
                                    val temp = status.temperature ?: 24.5
                                    val hum = status.humidity ?: 62.0
                                    updateClimateUI(temp, hum)

                                    // Dynamic flock count from AI status
                                    val count = status.flockCount ?: (status.handCount ?: (status.detections?.size ?: 0))
                                    val healthy = status.healthyCount ?: count
                                    val flagged = (status.warningCount ?: 0) + (status.unhealthyCount ?: 0)
                                    liveFlockCount = count
                                    liveHealthyCount = healthy
                                    liveFlaggedCount = flagged
                                    updateFlockMetrics(count, healthy, flagged)

                                    // Save to Firebase Realtime Database
                                    val activeZone = binding.tvActiveZoneName.text.toString()
                                    com.example.poultrysense.data.repository.EnvironmentRepository.syncEnvironmentData(
                                        temperature = temp,
                                        humidity = hum,
                                        zone = activeZone
                                    )
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
        stopStreamPolling()
        stopSensorPolling()
        _binding = null
    }
}

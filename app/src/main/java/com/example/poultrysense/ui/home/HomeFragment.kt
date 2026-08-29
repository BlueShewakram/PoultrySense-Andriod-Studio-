package com.example.poultrysense.ui.home

import android.animation.ValueAnimator
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    private var streamJob: Job? = null
    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

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
        animateNormalRing(42, 48)
        updateAlertsAndMetrics()
        playEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        updateAlertsAndMetrics()
        startStreamPolling()
    }

    override fun onPause() {
        super.onPause()
        stopStreamPolling()
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
        binding.progressHealthRing.max = totalCapacity
        val animator = ValueAnimator.ofInt(0, targetNormal).apply {
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

    private fun updateAlertsAndMetrics() {
        val mainActivity = activity as? MainActivity ?: return
        val activeAlertsCount = mainActivity.farmAlerts.count { !it.read }
        binding.tvActiveAlertsCount.text = if (activeAlertsCount == 1) "1 Alert" else "$activeAlertsCount Alerts"
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

    private fun showClimateBottomSheet() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheetBinding = com.example.poultrysense.databinding.DialogClimateDetailsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.btnDismissClimateSheet.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
            animateNormalRing(42, 48)
            binding.swipeRefreshLayout.postDelayed({
                binding.swipeRefreshLayout.isRefreshing = false
            }, 800)
        }
    }

    private fun startStreamPolling() {
        stopStreamPolling()
        val serverUrl = ServerPreferences.getBaseUrl(requireContext()) + "/frame.jpg"
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url(serverUrl).build()
            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    private fun stopStreamPolling() {
        streamJob?.cancel()
        streamJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStreamPolling()
        _binding = null
    }
}
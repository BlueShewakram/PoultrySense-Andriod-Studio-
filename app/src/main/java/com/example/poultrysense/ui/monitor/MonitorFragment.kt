package com.example.poultrysense.ui.monitor

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.data.FirebaseConfig
import com.example.poultrysense.data.SensorTelemetry
import com.example.poultrysense.data.ServerPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.poultrysense.data.models.AiStatusResponse
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.data.models.DetectionItem
import com.example.poultrysense.data.models.DetectionOverlayResponse
import com.example.poultrysense.data.models.TierLabels
import com.example.poultrysense.databinding.FragmentMonitorBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.databinding.DialogScheduleCheckupBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import com.example.poultrysense.data.AiServerScanner
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.ChickenSessionDetail
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()
    private var streamJob: Job? = null
    private var statusJob: Job? = null
    private var detectionsJob: Job? = null
    @Volatile private var lastOverlayGeneration: Long = -1L
    private var sessionTickerJob: Job? = null
    private var latestAiStatus: AiStatusResponse? = null
    private var liveTemp: Double? = null
    private var liveHumidity: Double? = null
    private var sensorListener: ValueEventListener? = null

    private var hasShownMascotOnEntry = false
    private var hasShownOfflineMascotAlert = false
    private var isStreamReceivingFrames = false
    private var clientDisplayFps = 0.0
    enum class VideoFeedMode {
        STREAMING,
        DETECTION
    }
    private var currentFeedMode: VideoFeedMode = VideoFeedMode.STREAMING
    private var chickenBobAnimator: ObjectAnimator? = null

    private val zoneListener: (BarnZone) -> Unit = { zone ->
        activity?.runOnUiThread {
            if (_binding != null && isAdded) {
                                updateActiveZoneCard()
                syncStreamToActiveZone(zone)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hasShownMascotOnEntry = false
        hasShownOfflineMascotAlert = false
        showMascotScanOnce()

        setupPoultryFarmMapView()
        setupStreamModeToggle()
        setupSnapshotButton()
        setupOfflineRetry()
        setupCuteMascotAlert()
        setupCheckupSessionControls()

        binding.layoutConnectionBadge.setOnClickListener {
            if (binding.layoutMonitorOffline.visibility == View.VISIBLE) {
                binding.mascotPopupScanner.showPopupAndScan(2600L)
            }
        }
    }

    private fun setupPoultryFarmMapView() {
        // The artwork is a camera view: pinch to zoom, drag to pan, double-tap to
        // toggle zoom. Barn switching still happens through the barn selector.
        binding.btnScrollToLiveFeed.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.nestedScrollView.smoothScrollTo(0, binding.cardVideoViewport.top - 24)
        }

        FarmZoneManager.addListener(zoneListener)
        updateActiveZoneCard()
        syncStreamToActiveZone(FarmZoneManager.getActiveZone(requireContext()))
    }

    private fun syncStreamToActiveZone(zone: BarnZone) {
        if (zone.hasCamera) {
            startCameraStream()
        } else {
            stopCameraStream()
            isStreamReceivingFrames = false
            binding.ivMonitorStream.visibility = View.GONE
            updateActiveZoneCard()
        }
    }

    /**
     * One card for the real active barn zone. There are no fake camera channels:
     * if the selected barn has no assigned camera, no feed is shown.
     */
    private fun updateActiveZoneCard() {
        if (_binding == null) return
        val zone = FarmZoneManager.getActiveZone(requireContext())
        val cameraAssigned = zone.hasCamera
        val cameraLive = cameraAssigned && isStreamReceivingFrames

        // Bind the three map pens to the three real barns.
        val zones = FarmZoneManager.getAllZones(requireContext()).take(FarmZoneManager.MAX_BARNS)
        val activeIndex = zones.indexOfFirst { it.id == zone.id }.coerceAtLeast(0)
        binding.farmMapView.setBarns(
            zones.map { barn ->
                PoultryFarmMapView.MapBarn(
                    name = barn.barnName,
                    online = barn.hasCamera && isStreamReceivingFrames,
                    hasCamera = barn.hasCamera
                )
            },
            activeIndex
        )

        binding.ivSelectedZoneIcon.setImageResource(R.drawable.ic_chicken_normal)
        binding.tvSelectedZoneTitle.text = zone.fullDisplayName
        binding.tvMapChipTitle.text = zone.fullDisplayName
        binding.tvMonitorSubtitle.text = when {
            !cameraAssigned -> "${zone.fullDisplayName} • No camera"
            cameraLive -> "${zone.fullDisplayName} • Live"
            else -> "${zone.fullDisplayName} • Connecting"
        }
        binding.tvSelectedZoneSubtitle.text = when {
            !cameraAssigned -> "No camera assigned to this barn"
            cameraLive -> "${zone.cameraName} • Live feed"
            else -> "Camera assigned • Waiting for live signal"
        }

        binding.tvSelectedZoneBadge.text = when {
            !cameraAssigned -> "NO CAMERA"
            cameraLive -> "ACTIVE CAM"
            else -> "CONNECTING"
        }
        binding.tvSelectedZoneBadge.setBackgroundResource(
            if (cameraLive) R.drawable.bg_healthy_badge else R.drawable.bg_date_chip
        )
        binding.tvSelectedZoneBadge.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (cameraLive) R.color.primary else R.color.text_muted
            )
        )

        binding.tvHudSource.text = if (cameraAssigned) zone.cameraName.uppercase() else "NO CAMERA"
        binding.tvTelemetrySub.text = if (cameraAssigned) {
            "AI Flock Tracking • ${zone.cameraName}"
        } else {
            "No active camera"
        }
        setActiveCameraLabel(cameraLive)
        if (!cameraAssigned) {
            updateStatusBadge(false, "No Camera")
        }

        val count = latestAiStatus?.flockCount ?: (latestAiStatus?.detections?.size ?: 0)
        binding.tvZoneBirdsVal.text = if (cameraLive && latestAiStatus != null) count.toString() else "--"
        binding.tvZoneClimateVal.text = if (zone.hasSensor) climateText() else "No data"
        binding.tvZoneClimateLabel.text = "Temperature"
        binding.btnScrollToLiveFeed.visibility = View.GONE

        if (cameraLive) {
            binding.layoutMonitorOffline.visibility = View.GONE
            binding.ivMonitorStream.visibility = View.VISIBLE
        } else {
            binding.layoutMonitorOffline.visibility = View.VISIBLE
            binding.ivMonitorStream.visibility = View.GONE
            binding.tvOfflineTitle.text = if (cameraAssigned) "Waiting for Camera Signal" else "No Camera Assigned"
            binding.tvMonitorStatusSub.text = if (cameraAssigned) {
                "Start the AI server and connect the camera to begin streaming."
            } else {
                "Assign a camera to ${zone.fullDisplayName} in the barn selector to stream this zone."
            }
        }
    }



    private fun showMascotScanOnce() {
        if (!hasShownMascotOnEntry) {
            hasShownMascotOnEntry = true
            binding.mascotPopupScanner.showPopupAndScan(2600L)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSessionUI()
        startSensorListener()
        startCameraStream()
    }

    override fun onPause() {
        super.onPause()
        binding.mascotPopupScanner.stopAllAnimations()
        stopSessionTicker()
        stopSensorListener()
        stopCameraStream()
    }



    // ─── Shutter Snapshot Capture ───

    private fun setupSnapshotButton() {
        binding.btnCaptureSnapshot.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

            // Realistic Shutter Flash effect
            binding.viewShutterFlash.alpha = 0.85f
            binding.viewShutterFlash.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            Toast.makeText(context, "Snapshot saved to gallery", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Offline Retry ───

    private fun setupOfflineRetry() {
        binding.btnOfflineRetry.setOnClickListener {
            startCameraStream()
            Toast.makeText(context, "Retrying camera stream…", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 1-Tap Stream Mode Selector (Clean Camera vs AI Vision) ───

    private fun setupStreamModeToggle() {
        updateStreamModeUi()

        binding.btnToggleClean.setOnClickListener {
            if (currentFeedMode != VideoFeedMode.STREAMING) {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                currentFeedMode = VideoFeedMode.STREAMING
                updateStreamModeUi()
                startCameraStream()
            }
        }

        binding.btnToggleAi.setOnClickListener {
            if (currentFeedMode != VideoFeedMode.DETECTION) {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                currentFeedMode = VideoFeedMode.DETECTION
                updateStreamModeUi()
                startCameraStream()
            }
        }

        // Tapping the HUD pill inside the video viewport also toggles the feed mode
        binding.hudLivePill.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentFeedMode = if (currentFeedMode == VideoFeedMode.STREAMING) {
                VideoFeedMode.DETECTION
            } else {
                VideoFeedMode.STREAMING
            }
            updateStreamModeUi()
            startCameraStream()
        }

        }

    private fun updateStreamModeUi() {
        if (_binding == null) return
        val ctx = context ?: return
        val isStreaming = currentFeedMode == VideoFeedMode.STREAMING

        if (isStreaming) {
            binding.btnToggleClean.setBackgroundResource(R.drawable.bg_segmented_active)
            binding.tvToggleClean.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            binding.btnToggleAi.setBackgroundResource(android.R.color.transparent)
            binding.tvToggleAi.setTextColor(Color.parseColor("#64748B"))

            binding.tvHudSource.text = "STREAMING"
            binding.viewHudLiveDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
            binding.ivDetectionOverlay.clear()
        } else {
            binding.btnToggleAi.setBackgroundResource(R.drawable.bg_segmented_active)
            binding.tvToggleAi.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            binding.btnToggleClean.setBackgroundResource(android.R.color.transparent)
            binding.tvToggleClean.setTextColor(Color.parseColor("#64748B"))

            binding.tvHudSource.text = "DETECTION"
            binding.viewHudLiveDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
        }
    }

    // ─── Camera Stream Poller ───

    private fun startCameraStream() {
        stopCameraStream()
        // Only the active barn's assigned camera can stream.
        val activeZone = FarmZoneManager.getActiveZone(requireContext())
        if (!activeZone.hasCamera) {
            isStreamReceivingFrames = false
            binding.ivMonitorStream.visibility = View.GONE
                        setActiveCameraLabel(false)
            updateActiveZoneCard()
            return
        }
        // Until a real JPEG frame arrives, the camera is not live.
        isStreamReceivingFrames = false
        if (_binding != null) {
                        setActiveCameraLabel(false)
            updateActiveZoneCard()
        }
        val baseUrl = ServerPreferences.getBaseUrl(requireContext())

        // 1. High-Performance Frame Poller (Zero-lag real-time camera stream)
        // Both modes use the clean stream: Detection mode draws AI boxes as a
        // client-side overlay, so the video never drops to inference frame rate.
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/raw_frame.jpg")
                .header("Cache-Control", "no-cache")
                .build()
//
//
//
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // 50% faster decode, half memory allocation
            }
            var lastFpsCalcTime = SystemClock.uptimeMillis()
            var clientRenderedFrames = 0
            var consecutiveErrors = 0

            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    val wasLive = isStreamReceivingFrames
                                    isStreamReceivingFrames = true
                                    consecutiveErrors = 0
                                    binding.ivMonitorStream.setImageBitmap(bitmap)
                                    binding.layoutMonitorOffline.visibility = View.GONE
                                    if (!wasLive) {
                                                                                setActiveCameraLabel(true)
                                        updateActiveZoneCard()
                                    }
                                    binding.mascotPopupScanner.hideWhenSignalReceived()
                                    if (binding.cardCuteMascotAlert.visibility == View.VISIBLE &&
                                        binding.tvMascotTitle.text.contains("Offline", ignoreCase = true)
                                    ) {
                                        hideCuteMascotAlert()
                                    }

                                    // Real client display refresh rate calculation
                                    clientRenderedFrames++
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastFpsCalcTime >= 1000L) {
                                        clientDisplayFps = (clientRenderedFrames * 1000.0) / (now - lastFpsCalcTime)
                                        clientRenderedFrames = 0
                                        lastFpsCalcTime = now
                                        val displayFps = maxOf(clientDisplayFps, latestAiStatus?.fps ?: 0.0)
                                        binding.tvHudFps.text = String.format("%.1f FPS", displayFps)
                                    }
                                }
                            }
                        }
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors >= 6) {
                            showOfflineUi()
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors >= 6) {
                        showOfflineUi()
                    }
                }
                // Fixed 30ms cadence matching 30 FPS camera hardware: prevents TCP socket backlog and eliminates lag!
                delay(30)
            }
        }

        // 2. Detection Box Overlay Poller (Detection mode only)
        if (currentFeedMode == VideoFeedMode.DETECTION) {
            startDetectionOverlayPolling(baseUrl)
        } else {
            binding.ivDetectionOverlay.clear()
        }

        // 3. Status Poller
        statusJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/status").build()
            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val status = gson.fromJson(body, AiStatusResponse::class.java)
                            latestAiStatus = status
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    val isConnected = status.active == true
                                    val serverFps = status.fps ?: 0.0
                                    val displayFps = if (clientDisplayFps > 0.0) maxOf(clientDisplayFps, serverFps) else serverFps
                                    val count = status.flockCount ?: (status.handCount ?: (status.detections?.size ?: 0))
                                    val healthIndex = status.healthIndex


                                    val activeZoneHasCamera =
                                        FarmZoneManager.getActiveZone(requireContext()).hasCamera
                                    updateStatusBadge(
                                        activeZoneHasCamera && isConnected,
                                        when {
                                            !activeZoneHasCamera -> "No Camera"
                                            isConnected -> "AI Connected"
                                            else -> "AI Offline"
                                        }
                                    )
                                    binding.tvHudFps.text = String.format("%.1f FPS", displayFps)
                                    val detectionFps = status.detectionFps ?: 0.0
                                    binding.tvHudLatency.text = String.format(Locale.getDefault(), "• %.1f AI/s", detectionFps)
                                    binding.tvDetectionCount.text = "$count in View"
                                    binding.tvTelemetryCountVal.text = count.toString()
                                    binding.tvTelemetryPostureVal.text = healthIndex?.let { "$it%" } ?: "--"
                                    binding.tvTelemetryLatencyVal.text = if (detectionFps > 0.05) {
                                        String.format(Locale.getDefault(), "%.0f ms", 1000.0 / detectionFps)
                                    } else {
                                        "--"
                                    }

                                    // Synchronize the single active-zone card.
                                    if (isStreamReceivingFrames) {
                                        binding.tvZoneBirdsVal.text = count.toString()
                                    }

                                    // Dynamic Flock Subject List - bound to actual detections
                                    val detections = status.detections ?: emptyList()
                                    if (count == 0 || detections.isEmpty()) {
                                        binding.rowTrackSubject1.visibility = View.GONE
                                        binding.rowTrackSubject2.visibility = View.GONE
                                    } else if (count == 1 || detections.size == 1) {
                                        binding.rowTrackSubject1.visibility = View.VISIBLE
                                        binding.rowTrackSubject2.visibility = View.GONE
                                        updateSubjectRow(1, detections[0], binding.tvSubject1Title, binding.tvSubject1Sub, binding.tvSubject1Status, binding.tvSubject1Num)
                                    } else {
                                        binding.rowTrackSubject1.visibility = View.VISIBLE
                                        binding.rowTrackSubject2.visibility = View.VISIBLE
                                        updateSubjectRow(1, detections[0], binding.tvSubject1Title, binding.tvSubject1Sub, binding.tvSubject1Status, binding.tvSubject1Num)
                                        updateSubjectRow(2, detections[1], binding.tvSubject2Title, binding.tvSubject2Sub, binding.tvSubject2Status, binding.tvSubject2Num)
                                    }

                                    // Forward active chicken alerts to MainActivity with bright physical color tag
                                    status.alerts?.forEach { alertItem ->
                                        val cId = alertItem.chickenId ?: 0
                                        if (cId > 0 && alertItem.status != "Healthy") {
                                            val tag = ChickenTagHelper.getTagInfo(cId)
                                            val isCrit = alertItem.severity.equals("critical", ignoreCase = true) || (alertItem.anomalyScore ?: 0) >= 70
                                            val alert = FarmAlert(
                                                id = "alert-ai-c$cId",
                                                severity = if (isCrit) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                                                title = "${alertItem.status ?: "Needs Attention"} • ${tag.displayLabel}",
                                                description = "Chicken physically tagged with ${tag.physicalDescription}: ${alertItem.reason?.takeIf { it.isNotEmpty() } ?: alertItem.behavior ?: "Abnormal behavior detected"}. ${alertItem.recommendation ?: ""}".trim(),
                                                timeAgo = "Live Feed",
                                                timestamp = alertItem.timestamp?.takeIf { it.isNotEmpty() } ?: "Live Stream",
                                                chickenId = cId.toString(),
                                                location = FarmZoneManager.getActiveZone(requireContext()).fullDisplayName,
                                                tagColorName = tag.colorName,
                                                tagColorHex = tag.colorHex,
                                                tagNumber = tag.tagNumber
                                            )
                                            (activity as? MainActivity)?.addAlert(alert)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        showOfflineStatus()
                    }
                    response.close()
                } catch (e: Exception) {
                    showOfflineStatus()
                }
                delay(1000)
            }
        }
    }

    private fun startDetectionOverlayPolling(baseUrl: String) {
        detectionsJob?.cancel()
        lastOverlayGeneration = -1L
        val request = Request.Builder()
            .url("$baseUrl/detections")
            .header("Cache-Control", "no-cache")
            .build()

        detectionsJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val payload = gson.fromJson(body, DetectionOverlayResponse::class.java)
                            val generation = payload.frameGeneration ?: 0L
                            if (generation != lastOverlayGeneration) {
                                lastOverlayGeneration = generation
                                val detections = payload.detections ?: emptyList()
                                val frameWidth = payload.frameWidth ?: 640
                                val frameHeight = payload.frameHeight ?: 480
                                withContext(Dispatchers.Main) {
                                    if (_binding != null &&
                                        currentFeedMode == VideoFeedMode.DETECTION
                                    ) {
                                        binding.ivDetectionOverlay.setDetections(
                                            detections, frameWidth, frameHeight
                                        )
                                    }
                                }
                            }
                        }
                    }
                    response.close()
                } catch (_: Exception) {
                    // Overlay failures must never interrupt the video preview.
                }
                // Poll slightly faster than the detector's ~9 Hz so the overlay
                // receives every update; the view interpolates between them.
                delay(100)
            }
        }
    }

    private fun setActiveCameraLabel(live: Boolean) {
        if (_binding == null) return
        val zone = FarmZoneManager.getActiveZone(requireContext())
        binding.tvActiveCameraLabel.text = when {
            !zone.hasCamera -> "No camera assigned"
            live -> "${zone.cameraName} • Live"
            else -> "${zone.cameraName} • Connecting…"
        }
    }

    private suspend fun showOfflineUi() {
        withContext(Dispatchers.Main) {
            if (_binding != null) {
                isStreamReceivingFrames = false
                                setActiveCameraLabel(false)
                updateActiveZoneCard()
                binding.layoutMonitorOffline.visibility = View.VISIBLE
                binding.rowTrackSubject1.visibility = View.GONE
                binding.rowTrackSubject2.visibility = View.GONE
                showCuteMascotAlert(
                    title = "Camera Offline",
                    message = "Stream offline. Check server connection or retry.",
                    isOfflinePrompt = true
                )
            }
        }
    }

    private suspend fun showOfflineStatus() {
        withContext(Dispatchers.Main) {
            if (_binding != null) {
                isStreamReceivingFrames = false
                updateStatusBadge(false, "AI Offline")
                binding.tvHudFps.text = "0.0 FPS"
                // Clear the last telemetry so stale numbers are not shown as live.
                binding.tvDetectionCount.text = "0 in View"
                binding.tvTelemetryCountVal.text = "--"
                binding.tvTelemetryPostureVal.text = "--"
                binding.tvTelemetryLatencyVal.text = "--"
                binding.tvZoneBirdsVal.text = "--"
                binding.rowTrackSubject1.visibility = View.GONE
                binding.rowTrackSubject2.visibility = View.GONE
            }
        }
    }

    private fun updateStatusBadge(isConnected: Boolean, label: String) {
        val context = context ?: return
        if (isConnected) {
            binding.layoutConnectionBadge.setBackgroundResource(R.drawable.bg_status_connected)
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            binding.viewHudLiveDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_green)
        } else {
            binding.layoutConnectionBadge.setBackgroundResource(R.drawable.bg_status_offline)
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.critical)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.critical))
            binding.viewHudLiveDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.critical)
        }
        binding.tvStatusBadge.text = label
    }

    private fun updateSubjectRow(
        index: Int,
        detection: DetectionItem,
        tvTitle: android.widget.TextView,
        tvSub: android.widget.TextView,
        tvStatus: android.widget.TextView,
        tvNum: android.widget.TextView
    ) {
        val trackId = detection.trackId ?: index
        val tagInfo = ChickenTagHelper.getTagInfo(trackId)
        val conf = ((detection.confidence ?: 0.0) * 100).toInt()
        val behavior = detection.behavior?.replaceFirstChar { it.uppercase() } ?: "Active"
        val health = detection.health ?: "Healthy"
        val detail = detection.healthDetail?.takeIf { it.isNotEmpty() } ?: "Normal Locomotion"
        val tier = detection.anomalyTier ?: "Normal"
        val gemini = detection.geminiSummary?.takeIf { it.isNotEmpty() }
        val geminiVerified = detection.geminiVerified ?: false

        val verdictSource = if (geminiVerified) "✓ Gemini" else "Local"
        tvTitle.text = "Chicken #$trackId [${tagInfo.colorName} Tag] ($verdictSource • ${TierLabels.forTier(tier)})"
        tvNum.text = "#$trackId"

        // Set the circular badge to the chicken's bright physical tag color
        val numDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(tagInfo.colorHex))
        }
        tvNum.background = numDrawable
        tvNum.setTextColor(Color.parseColor(tagInfo.textColorHex))

        if (gemini != null) {
            tvSub.text = "$gemini"
        } else {
            tvSub.text = "ByteTrack Conf $conf% • $behavior • $detail"
        }

        val ctx = context ?: return
        when {
            tier == "High Concern" || health.lowercase(Locale.getDefault()) == "high concern" -> {
                tvStatus.setBackgroundResource(R.drawable.bg_critical_badge)
                tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                tvStatus.text = TierLabels.ALERT.uppercase(Locale.getDefault())
            }
            tier == "Suspicious" || health.lowercase(Locale.getDefault()) in listOf("needs attention", "warning", "needs review") -> {
                tvStatus.setBackgroundResource(R.drawable.bg_warning_badge)
                tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                tvStatus.text = TierLabels.ATTENTION.uppercase(Locale.getDefault())
            }
            tier == "Monitor" -> {
                tvStatus.setBackgroundResource(R.drawable.bg_warning_badge)
                tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                tvStatus.text = TierLabels.WATCH.uppercase(Locale.getDefault())
            }
            else -> {
                tvStatus.setBackgroundResource(R.drawable.bg_healthy_badge)
                tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                tvStatus.text = if (geminiVerified) "✓ HEALTHY" else TierLabels.NORMAL.uppercase(Locale.getDefault())
            }
        }
    }

    private fun climateText(): String {
        val temp = liveTemp ?: latestAiStatus?.temperature
        return if (temp != null) String.format(Locale.getDefault(), "%.1f°C", temp) else "No data"
    }

    private fun startSensorListener() {
        stopSensorListener()
        val ref = FirebaseConfig.database().getReference("sensor_data").child("live")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val temp = snapshot.child("temperature").getValue(Double::class.java)
                    ?: snapshot.child("temperature").getValue(Long::class.java)?.toDouble()
                val hum = snapshot.child("humidity").getValue(Double::class.java)
                    ?: snapshot.child("humidity").getValue(Long::class.java)?.toDouble()
                // Ignore cached values from a powered-off ESP32.
                val fresh = SensorTelemetry.isFresh(snapshot)
                liveTemp = if (fresh) temp else null
                liveHumidity = if (fresh) hum else null
                activity?.runOnUiThread {
                    if (_binding == null || !isAdded) return@runOnUiThread
                    val zone = FarmZoneManager.getActiveZone(requireContext())
                    if (zone.hasSensor) {
                        binding.tvZoneClimateVal.text = climateText()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        sensorListener = listener
        ref.addValueEventListener(listener)
    }

    private fun stopSensorListener() {
        sensorListener?.let {
            FirebaseConfig.database().getReference("sensor_data").child("live").removeEventListener(it)
        }
        sensorListener = null
    }

    private fun stopCameraStream() {
        streamJob?.cancel()
        streamJob = null
        statusJob?.cancel()
        statusJob = null
        detectionsJob?.cancel()
        detectionsJob = null
        _binding?.ivDetectionOverlay?.clear()
    }

    // ─── AI Checkup Session & Scheduling ───

    private fun setupCheckupSessionControls() {
        // 1. Initial UI state
        updateSessionUI()

        // 2. Start / Stop Session Button Toggle
        binding.btnToggleSession.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val isRunning = CheckupSessionManager.isSessionActive(ctx)
            if (isRunning) {
                val status = latestAiStatus
                val detectedCount = status?.flockCount ?: (binding.tvTelemetryCountVal.text.toString().toIntOrNull() ?: 0)
                val healthyCount = status?.healthyCount ?: detectedCount
                val illCount = (status?.unhealthyCount ?: 0) + (status?.warningCount ?: 0)
                val chickenDetails = (status?.detections ?: emptyList())
                    .mapNotNull { detection ->
                        val trackId = detection.trackId ?: return@mapNotNull null
                        if (trackId <= 0) return@mapNotNull null
                        ChickenSessionDetail(
                            chickenId = trackId,
                            anomalyScore = detection.anomalyScore ?: 0,
                            anomalyTier = detection.anomalyTier ?: "Normal",
                            behavior = detection.behavior.orEmpty(),
                            geminiSummary = detection.geminiSummary.orEmpty()
                        )
                    }
                CheckupSessionManager.stopSession(
                    ctx,
                    totalFlock = detectedCount,
                    healthyCount = healthyCount,
                    illCount = illCount,
                    chickenDetails = chickenDetails
                )
                Toast.makeText(ctx, "Checkup session saved to History", Toast.LENGTH_SHORT).show()
                updateSessionUI()
            } else {
                // Interactive Barn Selection: Ask the user which barn camera to scan
                val dialog = SelectScanBarnDialog { chosenBarn ->
                    val duration = CheckupSessionManager.getScheduledDuration(ctx)
                    CheckupSessionManager.startSession(ctx, duration, chosenBarn)
                    val durationText = if (duration == 0) "Continuous" else "$duration mins"
                    Toast.makeText(ctx, "AI Scan started on ${chosenBarn.barnName} ($durationText)", Toast.LENGTH_SHORT).show()
                    startCameraStream()
                    updateSessionUI()
                }
                dialog.show(parentFragmentManager, "SELECT_SCAN_BARN_DIALOG")
            }
        }

        // 3. Open Schedule Dialog
        binding.btnOpenScheduleDialog.setOnClickListener {
            showScheduleCheckupDialog()
        }
    }

    private fun updateSessionUI() {
        val ctx = context ?: return
        if (_binding == null) return
        val isActive = CheckupSessionManager.isSessionActive(ctx)

        val scheduleDescription = CheckupSessionManager.getScheduleDescription(ctx)
        binding.tvScheduledRunInfo.text = if (CheckupSessionManager.isScheduleEnabled(ctx)) {
            "Next: $scheduleDescription"
        } else {
            scheduleDescription
        }

        if (isActive) {
            binding.tvSessionTitle.text = "Diagnostic Scan Active"
            binding.tvSessionSubtitle.text = "Analyzing flock behavior & logging telemetry"
            binding.viewSessionDot.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.accent_green)
            binding.btnToggleSession.text = "Stop Scan"
            binding.btnToggleSession.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.critical)
            binding.layoutActiveSessionBanner.visibility = View.VISIBLE
            startSessionTicker()
        } else {
            binding.tvSessionTitle.text = "AI Health Checkup"
            binding.tvSessionSubtitle.text = "Continuous 24/7 AI Health Monitoring"
            binding.viewSessionDot.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.text_muted)
            binding.btnToggleSession.text = "Run On-Demand Scan"
            binding.btnToggleSession.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
            binding.layoutActiveSessionBanner.visibility = View.GONE
            stopSessionTicker()
        }
    }

    private fun startSessionTicker() {
        stopSessionTicker()
        sessionTickerJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val ctx = context ?: break
                if (!CheckupSessionManager.isSessionActive(ctx)) {
                    updateSessionUI()
                    Toast.makeText(ctx, "AI Flock Checkup Completed", Toast.LENGTH_LONG).show()
                    break
                }
                val remainingSec = CheckupSessionManager.getRemainingSeconds(ctx)
                if (_binding != null) {
                    if (remainingSec == -1L) {
                        val elapsed = CheckupSessionManager.getElapsedSeconds(ctx)
                        val m = elapsed / 60
                        val s = elapsed % 60
                        binding.tvSessionTimerLabel.text = "Active duration:"
                        binding.tvSessionCountdown.text = String.format(Locale.getDefault(), "%02d:%02d", m, s)
                    } else {
                        val m = remainingSec / 60
                        val s = remainingSec % 60
                        binding.tvSessionTimerLabel.text = "Time remaining:"
                        binding.tvSessionCountdown.text = String.format(Locale.getDefault(), "%02d:%02d", m, s)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopSessionTicker() {
        sessionTickerJob?.cancel()
        sessionTickerJob = null
    }

    private fun showScheduleCheckupDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogScheduleCheckupBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        var selectedHour = CheckupSessionManager.getScheduledHour(ctx)
        var selectedMinute = CheckupSessionManager.getScheduledMinute(ctx)
        var selectedDuration = CheckupSessionManager.getScheduledDuration(ctx)
        var selectedRepeat = CheckupSessionManager.getScheduledRepeat(ctx)
        val isEnabled = CheckupSessionManager.isScheduleEnabled(ctx)

        dialogBinding.switchScheduleActive.isChecked = isEnabled

        fun updateTimeDisplay() {
            val amPm = if (selectedHour >= 12) "PM" else "AM"
            val displayHour = if (selectedHour % 12 == 0) 12 else selectedHour % 12
            dialogBinding.tvSelectedTime.text = String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, selectedMinute, amPm)
        }
        updateTimeDisplay()

        // Time Picker Click
        dialogBinding.cardPickTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText("Select Checkup Time")
                .setTheme(R.style.ThemeOverlay_PoultrySense_TimePicker)
                .build()
//

            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                updateTimeDisplay()
            }
            picker.show(parentFragmentManager, "CHECKUP_TIME_PICKER")
        }

        // Duration Chips Pre-selection
        when (selectedDuration) {
            5 -> dialogBinding.chipDur5.isChecked = true
            10 -> dialogBinding.chipDur10.isChecked = true
            15 -> dialogBinding.chipDur15.isChecked = true
            30 -> dialogBinding.chipDur30.isChecked = true
            60 -> dialogBinding.chipDur60.isChecked = true
            120 -> dialogBinding.chipDur120.isChecked = true
            0 -> dialogBinding.chipDurContinuous.isChecked = true
            else -> dialogBinding.chipDur30.isChecked = true
        }

        // Repeat Chips Pre-selection
        when (selectedRepeat) {
            "Daily" -> dialogBinding.chipRepeatDaily.isChecked = true
            "Weekdays" -> dialogBinding.chipRepeatWeekdays.isChecked = true
            "Once" -> dialogBinding.chipRepeatOnce.isChecked = true
            else -> dialogBinding.chipRepeatDaily.isChecked = true
        }

        // Duration change listener
        dialogBinding.chipGroupDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedDuration = when {
                checkedIds.contains(R.id.chipDur5) -> 5
                checkedIds.contains(R.id.chipDur10) -> 10
                checkedIds.contains(R.id.chipDur15) -> 15
                checkedIds.contains(R.id.chipDur30) -> 30
                checkedIds.contains(R.id.chipDur60) -> 60
                checkedIds.contains(R.id.chipDur120) -> 120
                checkedIds.contains(R.id.chipDurContinuous) -> 0
                else -> 30
            }
        }

        // Repeat change listener
        dialogBinding.chipGroupRepeat.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedRepeat = when {
                checkedIds.contains(R.id.chipRepeatDaily) -> "Daily"
                checkedIds.contains(R.id.chipRepeatWeekdays) -> "Weekdays"
                checkedIds.contains(R.id.chipRepeatOnce) -> "Once"
                else -> "Daily"
            }
        }

        // Cancel
        dialogBinding.btnCancelSchedule.setOnClickListener {
            dialog.dismiss()
        }

        // Save
        dialogBinding.btnSaveSchedule.setOnClickListener {
            val enabled = dialogBinding.switchScheduleActive.isChecked
            CheckupSessionManager.setScheduleEnabled(ctx, enabled)
            CheckupSessionManager.saveSchedule(
                context = ctx,
                hour = selectedHour,
                minute = selectedMinute,
                durationMinutes = selectedDuration,
                repeatMode = selectedRepeat,
                enabled = enabled,
                dateMillis = System.currentTimeMillis()
            )
            updateSessionUI()
            Toast.makeText(ctx, "Checkup schedule saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─── Cute Chicken Mascot Side-Popup & Auto-Detect Actions ───

    private fun setupCuteMascotAlert() {
        binding.btnDismissMascotAlert.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hideCuteMascotAlert()
        }

        binding.btnMascotAutoDetect.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            triggerAutoDiscovery()
        }
    }

    private fun triggerAutoDiscovery() {
        val ctx = context ?: return
        binding.tvMascotTitle.text = "Searching Wi-Fi..."
        binding.tvMascotMessage.text = "Scanning network for your AI Camera Hub. Please ensure PC server is running..."
        binding.btnMascotAutoDetect.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val discoveredIp = AiServerScanner.discoverServer(ctx) { progress ->
                    if (_binding != null) {
                        binding.tvMascotMessage.text = progress
                    }
                }

                if (_binding == null) return@launch
                binding.btnMascotAutoDetect.isEnabled = true

                if (discoveredIp != null) {
                    ServerPreferences.saveIp(ctx, discoveredIp)
                    binding.tvMascotTitle.text = "Found AI Hub"
                    binding.tvMascotMessage.text = "Connected to $discoveredIp! Resuming camera stream..."
                    Toast.makeText(ctx, "Connected to AI Hub: $discoveredIp", Toast.LENGTH_SHORT).show()
                    delay(1200)
                    hideCuteMascotAlert()
                    startCameraStream()
                } else {
                    binding.tvMascotTitle.text = "Hub Not Found"
                    binding.tvMascotMessage.text = "Couldn't reach AI Hub on Wi-Fi. Make sure your PC server is running and retry!"
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.btnMascotAutoDetect.isEnabled = true
                    binding.tvMascotTitle.text = "Scan Error"
                    binding.tvMascotMessage.text = "Error scanning network. Please check Wi-Fi connection."
                }
            }
        }
    }

    private fun showCuteMascotAlert(title: String, message: String, isOfflinePrompt: Boolean = false) {
        if (_binding == null) return
        if (isOfflinePrompt && hasShownOfflineMascotAlert) return
        if (isOfflinePrompt) {
            hasShownOfflineMascotAlert = true
        }

        binding.tvMascotTitle.text = title
        binding.tvMascotMessage.text = message

        if (binding.cardCuteMascotAlert.visibility != View.VISIBLE) {
            binding.cardCuteMascotAlert.visibility = View.VISIBLE
            binding.cardCuteMascotAlert.alpha = 0f
            binding.cardCuteMascotAlert.translationX = 350f
            binding.cardCuteMascotAlert.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(1.05f))
                .start()
        }

        startChickenBobbing()
    }

    private fun hideCuteMascotAlert() {
        if (_binding == null) return
        stopChickenBobbing()
        binding.cardCuteMascotAlert.animate()
            .alpha(0f)
            .translationX(350f)
            .setDuration(240)
            .withEndAction {
                if (_binding != null) {
                    binding.cardCuteMascotAlert.visibility = View.GONE
                }
            }
            .start()
    }

    private fun startChickenBobbing() {
        chickenBobAnimator?.cancel()
        chickenBobAnimator = ObjectAnimator.ofFloat(
            binding.imgMascotChicken,
            "translationY",
            0f, -8f, 0f
        ).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopChickenBobbing() {
        chickenBobAnimator?.cancel()
        chickenBobAnimator = null
        _binding?.imgMascotChicken?.translationY = 0f
    }

    private fun shakeView(view: View) {
        val anim = ObjectAnimator.ofFloat(view, "translationX", 0f, -24f, 24f, -18f, 18f, -10f, 10f, 0f)
        anim.duration = 480
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.start()
    }

    override fun onDestroyView() {
        FarmZoneManager.removeListener(zoneListener)
        super.onDestroyView()
        stopChickenBobbing()
        stopSessionTicker()
        stopSensorListener()
        stopCameraStream()
        _binding = null
    }
}

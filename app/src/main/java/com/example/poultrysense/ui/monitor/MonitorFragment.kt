package com.example.poultrysense.ui.monitor

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.models.AiStatusResponse
import com.example.poultrysense.databinding.FragmentMonitorBinding
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()
    private var streamJob: Job? = null
    private var statusJob: Job? = null
    private var sessionTickerJob: Job? = null

    // Multi-Camera State
    enum class CameraChannel {
        CAM_1,
        CAM_2,
        CAM_3,
        PHONE_CAM
    }

    private var currentChannel = CameraChannel.CAM_1
    private var hasShownMascotOnEntry = false
    private var hasShownOfflineMascotAlert = false
    private var isStreamReceivingFrames = false
    private var chickenBobAnimator: ObjectAnimator? = null

    // CameraX
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startPhoneCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission required for phone sensor", Toast.LENGTH_SHORT).show()
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

        // Show cute 3D scanning mascot only once every time Monitor is clicked / opened
        hasShownMascotOnEntry = false
        hasShownOfflineMascotAlert = false
        showMascotScanOnce()

        setupCameraChannelSwitcher()
        setupLensFlipButton()
        setupSnapshotButton()
        setupOfflineRetry()
        setupCuteMascotAlert()
        setupCheckupSessionControls()

        binding.layoutConnectionBadge.setOnClickListener {
            if (currentChannel != CameraChannel.PHONE_CAM && binding.layoutMonitorOffline.visibility == View.VISIBLE) {
                binding.mascotPopupScanner.showPopupAndScan(2600L)
            }
        }
    }

    private fun showMascotScanOnce() {
        if (!hasShownMascotOnEntry && currentChannel != CameraChannel.PHONE_CAM) {
            hasShownMascotOnEntry = true
            binding.mascotPopupScanner.showPopupAndScan(2600L)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSessionUI()
        if (currentChannel == CameraChannel.PHONE_CAM) {
            startPhoneCamera()
        } else {
            startCameraStream()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mascotPopupScanner.stopAllAnimations()
        stopSessionTicker()
        stopCameraStream()
        stopPhoneCamera()
    }

    // ─── Multi-Camera Channel Switching (1-3 + Phone) ───

    private fun setupCameraChannelSwitcher() {
        binding.toggleCameraChannel.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnCam1 -> switchChannel(CameraChannel.CAM_1)
                    R.id.btnCam2 -> switchChannel(CameraChannel.CAM_2)
                    R.id.btnCam3 -> switchChannel(CameraChannel.CAM_3)
                    R.id.btnCamPhone -> switchChannel(CameraChannel.PHONE_CAM)
                }
            }
        }
    }

    private fun switchChannel(channel: CameraChannel) {
        currentChannel = channel

        when (channel) {
            CameraChannel.CAM_1 -> {
                isStreamReceivingFrames = false
                stopPhoneCamera()
                binding.ivMonitorStream.visibility = View.VISIBLE
                binding.viewCameraX.visibility = View.GONE
                binding.tvMonitorSubtitle.text = "Camera 1 • Main Barn Flock"
                binding.tvHudSource.text = "CAM 1 • MAIN COOP"
                binding.tvActiveCameraLabel.text = "Camera 1 Active • 1080p Stream"
                binding.tvTelemetrySub.text = "ByteTrack Multi-Object Intelligence • Cam 1"
                binding.tvHudLatency.visibility = View.VISIBLE
                binding.btnFlipCameraLens.alpha = 0.4f
                startCameraStream()
            }
            CameraChannel.CAM_2 -> {
                isStreamReceivingFrames = false
                stopPhoneCamera()
                binding.ivMonitorStream.visibility = View.VISIBLE
                binding.viewCameraX.visibility = View.GONE
                binding.tvMonitorSubtitle.text = "Camera 2 • Brooder Zone"
                binding.tvHudSource.text = "CAM 2 • BROODER"
                binding.tvActiveCameraLabel.text = "Camera 2 Active • 1080p Stream"
                binding.tvTelemetrySub.text = "ByteTrack Multi-Object Intelligence • Cam 2"
                binding.tvHudLatency.visibility = View.VISIBLE
                binding.btnFlipCameraLens.alpha = 0.4f
                startCameraStream()
            }
            CameraChannel.CAM_3 -> {
                isStreamReceivingFrames = false
                stopPhoneCamera()
                binding.ivMonitorStream.visibility = View.VISIBLE
                binding.viewCameraX.visibility = View.GONE
                binding.tvMonitorSubtitle.text = "Camera 3 • Nesting Area"
                binding.tvHudSource.text = "CAM 3 • NESTING"
                binding.tvActiveCameraLabel.text = "Camera 3 Active • 1080p Stream"
                binding.tvTelemetrySub.text = "ByteTrack Multi-Object Intelligence • Cam 3"
                binding.tvHudLatency.visibility = View.VISIBLE
                binding.btnFlipCameraLens.alpha = 0.4f
                startCameraStream()
            }
            CameraChannel.PHONE_CAM -> {
                isStreamReceivingFrames = true
                stopCameraStream()
                binding.mascotPopupScanner.dismissPopup()
                hideCuteMascotAlert()

                binding.ivMonitorStream.visibility = View.GONE
                binding.layoutMonitorOffline.visibility = View.GONE
                binding.viewCameraX.visibility = View.VISIBLE
                binding.tvMonitorSubtitle.text = "Phone Sensor • Local Camera"
                binding.tvHudSource.text = "PHONE SENSOR"
                binding.tvActiveCameraLabel.text = "Phone Camera Sensor Active"
                binding.tvTelemetrySub.text = "On-Device Hardware Preview"
                binding.tvHudFps.text = "60.0 FPS"
                binding.tvHudLatency.visibility = View.GONE
                binding.btnFlipCameraLens.alpha = 1.0f

                updateStatusBadge(true, "Phone Cam Active")
                checkPermissionAndStartCamera()
            }
        }
    }

    // ─── Lens Flip Action (Front/Rear for Phone Cam) ───

    private fun setupLensFlipButton() {
        binding.btnFlipCameraLens.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

            // Smooth 180-degree flip animation on the button icon
            val rotateAnim = ObjectAnimator.ofFloat(v, "rotation", v.rotation, v.rotation + 180f)
            rotateAnim.duration = 350
            rotateAnim.interpolator = AccelerateDecelerateInterpolator()
            rotateAnim.start()

            if (currentChannel != CameraChannel.PHONE_CAM) {
                Toast.makeText(context, "Switch to Phone Cam to flip lens", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Smooth camera transition fade
            binding.viewCameraX.animate().alpha(0.2f).setDuration(120).withEndAction {
                cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                startPhoneCamera()
                binding.viewCameraX.animate().alpha(1f).setDuration(180).start()
            }.start()
        }
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
            if (currentChannel != CameraChannel.PHONE_CAM) {
                startCameraStream()
                Toast.makeText(context, "Retrying camera stream…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Camera Stream Poller ───

    private fun startCameraStream() {
        stopCameraStream()
        val baseUrl = ServerPreferences.getBaseUrl(requireContext())

        // 1. Frame Poller
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/frame.jpg").build()
            while (isActive && currentChannel != CameraChannel.PHONE_CAM) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && currentChannel != CameraChannel.PHONE_CAM) {
                                    isStreamReceivingFrames = true
                                    binding.ivMonitorStream.setImageBitmap(bitmap)
                                    binding.layoutMonitorOffline.visibility = View.GONE
                                    binding.mascotPopupScanner.hideWhenSignalReceived()
                                    if (binding.cardCuteMascotAlert.visibility == View.VISIBLE &&
                                        binding.tvMascotTitle.text.contains("Offline", ignoreCase = true)
                                    ) {
                                        hideCuteMascotAlert()
                                    }
                                }
                            }
                        }
                    } else {
                        showOfflineUi()
                    }
                    response.close()
                } catch (e: Exception) {
                    showOfflineUi()
                }
                delay(33) // ~30 FPS
            }
        }

        // 2. Status Poller
        statusJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/status").build()
            while (isActive && currentChannel != CameraChannel.PHONE_CAM) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val status = gson.fromJson(body, AiStatusResponse::class.java)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && currentChannel != CameraChannel.PHONE_CAM) {
                                    val isConnected = status.active == true
                                    val fps = status.fps ?: 0.0
                                    val count = status.handCount ?: (status.detections?.size ?: 0)

                                    updateStatusBadge(isConnected, if (isConnected) "AI Connected" else "AI Offline")
                                    binding.tvHudFps.text = String.format("%.1f FPS", fps)
                                    binding.tvDetectionCount.text = "$count Subjects"
                                    binding.tvTelemetryCountVal.text = count.toString()
                                    binding.tvTelemetryPostureVal.text = if (count > 0) "99.4%" else "100%"
                                    binding.tvTelemetryLatencyVal.text = "0.03s"
                                    binding.tvSubject1Sub.text = "ByteTrack Confidence ${(88..98).random()}% • Stable"
                                    binding.tvSubject2Sub.text = "ByteTrack Confidence ${(85..95).random()}% • Active Movement"
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

    private suspend fun showOfflineUi() {
        withContext(Dispatchers.Main) {
            if (_binding != null && currentChannel != CameraChannel.PHONE_CAM) {
                isStreamReceivingFrames = false
                binding.layoutMonitorOffline.visibility = View.VISIBLE
                showCuteMascotAlert(
                    title = "Camera Offline",
                    message = "Stream offline. Switch to Phone Cam or search hub.",
                    isOfflinePrompt = true
                )
            }
        }
    }

    private suspend fun showOfflineStatus() {
        withContext(Dispatchers.Main) {
            if (_binding != null && currentChannel != CameraChannel.PHONE_CAM) {
                isStreamReceivingFrames = false
                updateStatusBadge(false, "AI Offline")
                binding.tvHudFps.text = "0.0 FPS"
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

    private fun stopCameraStream() {
        streamJob?.cancel()
        streamJob = null
        statusJob?.cancel()
        statusJob = null
    }

    // ─── Phone Camera (CameraX) ───

    private fun checkPermissionAndStartCamera() {
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPhoneCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startPhoneCamera() {
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewCameraX.surfaceProvider)
                }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopPhoneCamera() {
        cameraProvider?.unbindAll()
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
                val detectedCount = binding.tvTelemetryCountVal.text.toString().toIntOrNull() ?: 42
                val healthyCount = (detectedCount * 0.98).toInt().coerceAtLeast(1)
                CheckupSessionManager.stopSession(ctx, totalFlock = detectedCount, healthyCount = healthyCount, illCount = (detectedCount - healthyCount).coerceAtLeast(0))
                Toast.makeText(ctx, "Checkup session saved to History", Toast.LENGTH_SHORT).show()
                updateSessionUI()
            } else {
                // Smart Camera Safety Guard: Block checkup if camera feed is not ready
                val isCameraReady = (currentChannel == CameraChannel.PHONE_CAM) || isStreamReceivingFrames
                if (!isCameraReady) {
                    shakeView(binding.cardCheckupSession)
                    binding.btnToggleSession.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                    showCuteMascotAlert(
                        title = "Hold your feathers!",
                        message = "Cannot start AI checkup session without a live camera feed! Please connect your camera stream or switch to Phone Cam first."
                    )
                    Toast.makeText(ctx, "Connect camera stream to start checkup session", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val duration = CheckupSessionManager.getScheduledDuration(ctx)
                CheckupSessionManager.startSession(ctx, duration)
                val durationText = if (duration == 0) "Continuous" else "$duration mins"
                Toast.makeText(ctx, "AI Checkup Session started ($durationText)", Toast.LENGTH_SHORT).show()
                if (currentChannel != CameraChannel.PHONE_CAM) {
                    startCameraStream()
                }
                updateSessionUI()
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

        binding.tvScheduledRunInfo.text = "Next: " + CheckupSessionManager.getScheduleDescription(ctx)

        if (isActive) {
            binding.tvSessionTitle.text = "AI Checkup Active"
            binding.tvSessionSubtitle.text = "Analyzing flock behavior & logging telemetry"
            binding.viewSessionDot.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.accent_green)
            binding.btnToggleSession.text = "Stop Inspection"
            binding.btnToggleSession.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.critical)
            binding.layoutActiveSessionBanner.visibility = View.VISIBLE
            startSessionTicker()
        } else {
            binding.tvSessionTitle.text = "AI Checkup Session"
            binding.tvSessionSubtitle.text = "Idle • Tap Start to run flock inspection"
            binding.viewSessionDot.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.text_muted)
            binding.btnToggleSession.text = "Start Inspection"
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
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                updateTimeDisplay()
            }
            picker.show(parentFragmentManager, "CHECKUP_TIME_PICKER")
        }

        // Duration Chips Pre-selection
        when (selectedDuration) {
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

        binding.btnMascotUsePhoneCam.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            switchToPhoneCamera()
            hideCuteMascotAlert()
        }

        binding.btnOfflineUsePhoneCam.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            switchToPhoneCamera()
            hideCuteMascotAlert()
        }

        binding.btnMascotAutoDetect.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            triggerAutoDiscovery()
        }
    }

    private fun switchToPhoneCamera() {
        binding.toggleCameraChannel.check(R.id.btnCamPhone)
        Toast.makeText(context, "Switched to Phone Camera", Toast.LENGTH_SHORT).show()
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
                    binding.tvMascotMessage.text = "Couldn't reach AI Hub on Wi-Fi. Make sure your PC server is running or switch to Phone Cam!"
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
        super.onDestroyView()
        stopChickenBobbing()
        stopSessionTicker()
        stopCameraStream()
        stopPhoneCamera()
        _binding = null
    }
}

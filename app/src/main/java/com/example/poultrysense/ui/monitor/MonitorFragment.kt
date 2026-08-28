package com.example.poultrysense.ui.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.data.models.AiStatusResponse
import com.example.poultrysense.databinding.FragmentMonitorBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private val baseUrl = "http://192.168.254.106:5000"
    private var isLaptopSource = true

    // CameraX
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startPhoneCamera()
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

        setupSourceToggle()
        setupCameraSwitchButton()
    }

    override fun onResume() {
        super.onResume()
        if (isLaptopSource) {
            startLaptopStream()
        } else {
            startPhoneCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLaptopStream()
        stopPhoneCamera()
    }

    private fun setupSourceToggle() {
        binding.toggleCameraSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnSourceLaptop) {
                    isLaptopSource = true
                    stopPhoneCamera()
                    binding.ivMonitorStream.visibility = View.VISIBLE
                    binding.viewCameraX.visibility = View.GONE
                    binding.fabSwitchCameraLens.visibility = View.GONE
                    binding.tvHudSource.text = "1080p AI Vision"
                    startLaptopStream()
                } else {
                    isLaptopSource = false
                    stopLaptopStream()
                    binding.ivMonitorStream.visibility = View.GONE
                    binding.layoutMonitorOffline.visibility = View.GONE
                    binding.viewCameraX.visibility = View.VISIBLE
                    binding.fabSwitchCameraLens.visibility = View.VISIBLE
                    binding.tvHudSource.text = "Phone Sensor"
                    binding.tvHudFps.text = "60.0 FPS"
                    updateStatusBadge(true, "Phone Cam Active")
                    checkPermissionAndStartCamera()
                }
            }
        }
    }

    private fun setupCameraSwitchButton() {
        binding.fabSwitchCameraLens.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startPhoneCamera()
        }
    }

    // ─── Laptop Stream ───

    private fun startLaptopStream() {
        stopLaptopStream()

        // 1. Frame Poller
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/frame.jpg").build()
            while (isActive && isLaptopSource) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && isLaptopSource) {
                                    binding.ivMonitorStream.setImageBitmap(bitmap)
                                    binding.layoutMonitorOffline.visibility = View.GONE
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
            while (isActive && isLaptopSource) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val status = gson.fromJson(body, AiStatusResponse::class.java)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && isLaptopSource) {
                                    val isConnected = status.active == true
                                    val fps = status.fps ?: 0.0
                                    val count = status.handCount ?: (status.detections?.size ?: 0)

                                    updateStatusBadge(isConnected, if (isConnected) "AI Connected" else "AI Offline")
                                    binding.tvHudFps.text = String.format("%.1f FPS", fps)
                                    binding.tvDetectionCount.text = "$count Subjects"
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
            if (_binding != null && isLaptopSource) {
                binding.layoutMonitorOffline.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun showOfflineStatus() {
        withContext(Dispatchers.Main) {
            if (_binding != null && isLaptopSource) {
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
        } else {
            binding.layoutConnectionBadge.setBackgroundResource(R.drawable.bg_status_offline)
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.critical)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.critical))
        }
        binding.tvStatusBadge.text = label
    }

    private fun stopLaptopStream() {
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

    override fun onDestroyView() {
        super.onDestroyView()
        stopLaptopStream()
        stopPhoneCamera()
        _binding = null
    }
}

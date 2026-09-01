package com.example.poultrysense.ui.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()
    private var streamJob: Job? = null
    private var statusJob: Job? = null

    private var isLaptopSource = true
    private val isUploadingFrame = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

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

        binding.mascotPopupScanner.showPopupAndScan(2800L)

        binding.layoutConnectionBadge.setOnClickListener {
            binding.mascotPopupScanner.showPopupAndScan(2500L)
        }
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
        binding.mascotPopupScanner.stopAllAnimations()
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
                    binding.tvHudSource.text = "Laptop AI Vision"
                    startLaptopStream()
                } else {
                    isLaptopSource = false
                    stopLaptopStream()
                    binding.ivMonitorStream.visibility = View.VISIBLE
                    binding.layoutMonitorOffline.visibility = View.GONE
                    binding.viewCameraX.visibility = View.GONE
                    binding.fabSwitchCameraLens.visibility = View.VISIBLE
                    binding.tvHudSource.text = "Phone HD Sensor (AI Streaming)"
                    updateStatusBadge(true, "Phone AI Active")
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
        val context = context ?: return
        val baseUrl = ServerPreferences.getBaseUrl(context)

        // 1. Frame Poller
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("/frame.jpg").build()
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
            val request = Request.Builder().url("/status").build()
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
                                    val count = status.personCount ?: status.handCount ?: (status.detections?.size ?: 0)

                                    updateStatusBadge(isConnected, if (isConnected) "AI Connected" else "AI Offline")
                                    binding.tvHudFps.text = String.format("%.1f FPS", fps)
                                    binding.tvDetectionCount.text = " Subjects"
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

    // ─── Phone Camera (CameraX Streaming to YOLO AI Server) ───

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

                // Preview
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewCameraX.surfaceProvider)
                }

                // ImageAnalysis captures frames and streams them to YOLO AI Server
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val appContext = context ?: return@setAnalyzer
                    val baseUrl = ServerPreferences.getBaseUrl(appContext)

                    if (isLaptopSource || !isResumed) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    if (isUploadingFrame.compareAndSet(false, true)) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val bitmap = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val finalBitmap = if (rotation != 0) {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                } else {
                                    bitmap
                                }

                                val stream = ByteArrayOutputStream()
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                                val jpegBytes = stream.toByteArray()

                                val requestBody = jpegBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                                val request = Request.Builder()
                                    .url("/upload_frame")
                                    .post(requestBody)
                                    .build()

                                val response = uploadClient.newCall(request).execute()
                                if (response.isSuccessful) {
                                    val respBytes = response.body?.bytes()
                                    val personCount = response.header("X-Person-Count") ?: "0"
                                    val fps = response.header("X-FPS") ?: "30.0"

                                    if (respBytes != null && respBytes.isNotEmpty()) {
                                        val aiAnnotatedBitmap = BitmapFactory.decodeByteArray(respBytes, 0, respBytes.size)
                                        withContext(Dispatchers.Main) {
                                            if (_binding != null && !isLaptopSource) {
                                                binding.ivMonitorStream.setImageBitmap(aiAnnotatedBitmap)
                                                binding.layoutMonitorOffline.visibility = View.GONE
                                                updateStatusBadge(true, "Phone AI Active")
                                                binding.tvHudFps.text = " FPS"
                                                binding.tvDetectionCount.text = " Subjects"
                                            }
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        if (_binding != null && !isLaptopSource) {
                                            updateStatusBadge(false, "AI Offline")
                                        }
                                    }
                                }
                                response.close()
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    if (_binding != null && !isLaptopSource) {
                                        updateStatusBadge(false, "AI Offline")
                                    }
                                }
                            } finally {
                                isUploadingFrame.set(false)
                                imageProxy.close()
                            }
                        }
                    } else {
                        imageProxy.close()
                    }
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopPhoneCamera() {
        cameraProvider?.unbindAll()
        isUploadingFrame.set(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLaptopStream()
        stopPhoneCamera()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

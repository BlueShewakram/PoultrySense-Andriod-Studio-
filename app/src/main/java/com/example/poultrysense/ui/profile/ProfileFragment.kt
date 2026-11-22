package com.example.poultrysense.ui.profile

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.poultrysense.R
import com.example.poultrysense.data.AiServerScanner
import com.example.poultrysense.data.AppSettingsPreferences
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.FirebaseConfig
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import com.example.poultrysense.data.SensorTelemetry
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.UserPreferences
import com.example.poultrysense.data.WakeOnLanHelper
import com.example.poultrysense.data.models.AiStatusResponse
import com.example.poultrysense.data.models.User
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.data.repository.ProfileRepository
import com.example.poultrysense.databinding.DialogAiServerSettingsBinding
import com.example.poultrysense.databinding.DialogDesktopPowerBinding
import com.example.poultrysense.databinding.DialogHelpDiagnosticsBinding
import com.example.poultrysense.databinding.DialogNotificationPreferencesBinding
import com.example.poultrysense.databinding.DialogSystemSettingsBinding
import com.example.poultrysense.databinding.FragmentProfileBinding
import com.example.poultrysense.ui.auth.LoginActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()
    private fun profileRepository(): ProfileRepository = ProfileRepository(requireContext())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadUserData()
        loadOperationalStats()
        checkAiHubLiveStatus()
        setupClickListeners()
        bindAppVersion()
    }

    private fun bindAppVersion() {
        val ctx = context ?: return
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        binding.tvAppVersion.text = if (version.isBlank()) {
            "PoultrySense AI"
        } else {
            "PoultrySense AI v$version"
        }
    }

    private fun loadUserData() {
        val ctx = context ?: return
        val currentUser = authRepository.currentUser

        // 1. Instant display from the local cache (works offline / immediately after launch).
        val cachedUser = UserPreferences.getCachedUser(ctx)
        if (cachedUser != null) {
            displayUser(cachedUser)
        } else if (currentUser != null) {
            val fallback = authRepository.userFromFirebase(currentUser)
            displayUser(fallback)
        }

        // 2. Refresh from Firebase Realtime Database (the cross-device source of truth).
        if (currentUser != null) {
            lifecycleScope.launch {
                val fresh = profileRepository().refreshProfile(currentUser)
                if (_binding == null) return@launch
                if (fresh != null) {
                    displayUser(fresh)
                }
            }
        }
    }

    private var isAiHubOnline = false
    private var isVisionOnline = false
    private var isEsp32Online = false
    private var isAiServerPaused = false
    private var isSyncingPowerSwitch = false
    private var sensorStatusListener: ValueEventListener? = null
    private var aiServerPowerListener: ValueEventListener? = null
    private var aiServerIpListener: ValueEventListener? = null

    private fun setSwitchWithoutTriggeringListener(checked: Boolean, statusText: String) {
        if (_binding == null) return
        isSyncingPowerSwitch = true
        binding.switchAiServerPower.isChecked = checked
        binding.tvAiServerPowerStatus.text = statusText
        isSyncingPowerSwitch = false
    }

    private fun loadOperationalStats() {
        val ctx = context ?: return

        // 1. Hardware Readiness & Setup Progress
        updateHardwareReadiness()

        // 2. Server IP Info Subtext
        val ip = ServerPreferences.getIp(ctx)
        binding.tvAiServerSubtext.text = "$ip • Port 5000"

        // 3. Listen to Firebase and only count the sensor as online when its
        //    timestamp is fresh (older cached values are not "live").
        val realtimeDb = FirebaseConfig.database()
        val sensorRef = realtimeDb.getReference("sensor_data").child("live")
        sensorStatusListener?.let {
            sensorRef.removeEventListener(it)
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                isEsp32Online = SensorTelemetry.isFresh(snapshot)
                updateHardwareReadiness()
            }
            override fun onCancelled(error: DatabaseError) {
                isEsp32Online = false
                updateHardwareReadiness()
            }
        }
        sensorStatusListener = listener
        sensorRef.addValueEventListener(listener)

        // 4. Listen to Firebase Realtime Database for AI Server Power toggle state
        val aiServerRef = realtimeDb.getReference("system_control").child("ai_server")
        aiServerPowerListener?.let {
            aiServerRef.removeEventListener(it)
        }
        val aiPowerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                val value = snapshot.getValue(String::class.java) ?: "on"
                val isServerOn = value.equals("on", ignoreCase = true)
                isAiServerPaused = !isServerOn
                if (!isSyncingPowerSwitch) {
                    setSwitchWithoutTriggeringListener(
                        isServerOn,
                        if (isServerOn) "AI server running" else "AI server paused"
                    )
                }
                updateHardwareReadiness()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        aiServerPowerListener = aiPowerListener
        aiServerRef.addValueEventListener(aiPowerListener)

        // 5. Listen to Firebase Realtime Database for AI Server IP changes (automatic modem/port migration)
        val aiServerIpRef = realtimeDb.getReference("system_control").child("ai_server_ip")
        aiServerIpListener?.let {
            aiServerIpRef.removeEventListener(it)
        }
        val aiIpListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                val ctx = context ?: return
                val cloudIp = when (val v = snapshot.value) {
                    is String -> v.trim()
                    is Map<*, *> -> (v["ip"] as? String)?.trim()
                    else -> null
                }
                if (!cloudIp.isNullOrBlank()) {
                    val currentIp = ServerPreferences.getIp(ctx)
                    if (currentIp != cloudIp) {
                        ServerPreferences.saveIp(ctx, cloudIp)
                        binding.tvAiServerSubtext.text = "$cloudIp • Port 5000"
                        checkAiHubLiveStatus()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        aiServerIpListener = aiIpListener
        aiServerIpRef.addValueEventListener(aiIpListener)
    }

    private fun updateHardwareReadiness() {
        if (_binding == null) return
        val ctx = context ?: return
        val activeZone = FarmZoneManager.getActiveZone(ctx)

        // Four independent 25% modules. Nothing is assumed to be online.
        var score = 0
        val primaryColor = ContextCompat.getColor(ctx, R.color.primary)
        val mutedColor = ContextCompat.getColor(ctx, R.color.text_muted)
        val activeColor = android.graphics.Color.parseColor("#00B8D4")

        // 1. Account (signed in)
        val hasAccount = authRepository.currentUser != null
        if (hasAccount) score += 25
        binding.dotReadinessProfile.backgroundTintList = ColorStateList.valueOf(
            if (hasAccount) primaryColor else mutedColor
        )
        binding.tvReadinessProfileStatus.text = if (hasAccount) "Active" else "Sign in"
        binding.tvReadinessProfileStatus.setTextColor(if (hasAccount) primaryColor else mutedColor)

        // 2. AI Hub status
        if (isAiHubOnline) {
            if (isAiServerPaused) {
                score += 15
                binding.dotReadinessAi.backgroundTintList = ColorStateList.valueOf(mutedColor)
                binding.tvReadinessAiStatus.text = "Paused"
                binding.tvReadinessAiStatus.setTextColor(mutedColor)
            } else {
                score += 25
                binding.dotReadinessAi.backgroundTintList = ColorStateList.valueOf(activeColor)
                binding.tvReadinessAiStatus.text = "Online"
                binding.tvReadinessAiStatus.setTextColor(activeColor)
            }
        } else {
            binding.dotReadinessAi.backgroundTintList = ColorStateList.valueOf(mutedColor)
            binding.tvReadinessAiStatus.text = "Standby"
            binding.tvReadinessAiStatus.setTextColor(mutedColor)
        }

        // 3. Vision status (active camera stream)
        val visionLive = isVisionOnline
        if (visionLive) score += 25
        binding.dotReadinessCamera.backgroundTintList = ColorStateList.valueOf(
            if (visionLive) primaryColor else mutedColor
        )
        binding.tvReadinessCameraStatus.text = when {
            visionLive -> "Live"
            activeZone.hasCamera -> "No signal"
            else -> "No camera"
        }
        binding.tvReadinessCameraStatus.setTextColor(if (visionLive) primaryColor else mutedColor)

        // 4. ESP32 sensor status (only if the active zone actually has a sensor)
        val sensorLive = activeZone.hasSensor && isEsp32Online
        if (sensorLive) score += 25
        binding.dotReadinessEsp32.backgroundTintList = ColorStateList.valueOf(
            if (sensorLive) primaryColor else mutedColor
        )
        binding.tvReadinessEsp32Status.text = when {
            !activeZone.hasSensor -> "No sensor"
            isEsp32Online -> "Live"
            else -> "No data"
        }
        binding.tvReadinessEsp32Status.setTextColor(if (sensorLive) primaryColor else mutedColor)

        binding.progressHardwareReadiness.progress = score
        binding.tvReadinessPercent.text = "$score%"
        binding.tvReadinessSubtitle.text = when {
            score == 100 -> "All modules operational and synchronized"
            score >= 75 -> "Core modules active • Ready for monitoring"
            score >= 50 -> "Partially configured • Pair the missing hardware"
            else -> "Configure your account and hardware to begin"
        }
    }

    private fun checkAiHubLiveStatus() {
        val ctx = context ?: return
        var ip = ServerPreferences.getIp(ctx)

        lifecycleScope.launch(Dispatchers.IO) {
            var url = "http://$ip:5000/status"
            var hubOnline = false
            var visionOnline = false
            var isPaused = false
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    hubOnline = true
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val status = Gson().fromJson(body, AiStatusResponse::class.java)
                        isPaused = status?.paused == true
                        // "active" means the AI server is actually processing frames.
                        visionOnline = status?.active == true && !isPaused
                    }
                }
                response.close()
            } catch (_: Exception) {
            }

            // If saved IP is unreachable, attempt automatic cloud recovery via Firebase
            if (!hubOnline) {
                val cloudIp = AiServerScanner.fetchCloudServerIp()
                if (!cloudIp.isNullOrBlank() && cloudIp != ip) {
                    try {
                        val fallbackUrl = "http://$cloudIp:5000/status"
                        val request = Request.Builder().url(fallbackUrl).build()
                        val response = httpClient.newCall(request).execute()
                        if (response.isSuccessful) {
                            hubOnline = true
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val status = Gson().fromJson(body, AiStatusResponse::class.java)
                                isPaused = status?.paused == true
                                visionOnline = status?.active == true && !isPaused
                            }
                            ip = cloudIp
                            ServerPreferences.saveIp(ctx, cloudIp)
                        }
                        response.close()
                    } catch (_: Exception) {}
                }
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                isAiHubOnline = hubOnline
                isVisionOnline = visionOnline
                isAiServerPaused = isPaused
                binding.tvAiServerSubtext.text = "$ip • Port 5000"
                if (hubOnline) {
                    setSwitchWithoutTriggeringListener(
                        !isPaused,
                        if (!isPaused) "AI server running" else "AI server paused"
                    )
                } else {
                    setSwitchWithoutTriggeringListener(
                        false,
                        "AI server offline • $ip:5000"
                    )
                }
                updateHardwareReadiness()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            checkAiHubLiveStatus()
        }
    }

    /**
     * Resolves the proper image source for Glide:
     * - Base64 data URL ("data:image/jpeg;base64,...") -> ByteArray
     * - Local file:// URI -> File
     * - Permanent internal storage file ("profile_avatar.jpg") -> File
     * - Remote HTTP URL (excluding obsolete unsplash URLs) -> String
     * - Fallback: R.drawable.ic_person (never hardcoded online photos)
     */
    private fun resolveAvatarSource(ctx: Context, rawAvatar: String?): Any {
        val localFile = File(ctx.filesDir, "profile_avatar.jpg")

        // 1. Base64 data URI or long Base64 payload
        if (!rawAvatar.isNullOrEmpty() && (rawAvatar.startsWith("data:image") || rawAvatar.length > 100)) {
            try {
                val base64Data = if (rawAvatar.contains("base64,")) rawAvatar.substringAfter("base64,") else rawAvatar
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (bytes != null && bytes.isNotEmpty()) {
                    if (!localFile.exists() || localFile.length() == 0L) {
                        try { localFile.writeBytes(bytes) } catch (_: Exception) {}
                    }
                    return bytes
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Explicit file:// URI
        if (!rawAvatar.isNullOrEmpty() && rawAvatar.startsWith("file://")) {
            try {
                val path = Uri.parse(rawAvatar).path
                if (!path.isNullOrEmpty()) {
                    val f = File(path)
                    if (f.exists() && f.length() > 0) return f
                }
            } catch (_: Exception) {}
        }

        // 3. Permanent internal storage file on device
        if (localFile.exists() && localFile.length() > 0) {
            return localFile
        }

        // 4. Remote HTTP URL (excluding any old unsplash placeholder)
        if (!rawAvatar.isNullOrEmpty() && rawAvatar.startsWith("http") && !rawAvatar.contains("unsplash.com")) {
            return rawAvatar
        }

        // 5. Default fallback: Clean vector icon (NEVER a hardcoded photo!)
        return R.drawable.ic_person
    }

    private fun displayUser(user: User) {
        val ctx = context ?: return
        if (_binding == null) return
        binding.tvProfileName.text = user.fullName
        val subtitle = listOfNotNull(user.role?.trim(), user.farmName?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
        binding.tvProfileRole.text = subtitle.ifEmpty { "Farm profile" }
        binding.tvProfileEmail.text = user.email.orEmpty()

        val avatarSource = resolveAvatarSource(ctx, user.avatarUrl)

        if (avatarSource is Int) {
            binding.ivProfileAvatar.setImageResource(avatarSource)
        } else {
            Glide.with(this@ProfileFragment)
                .load(avatarSource)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(binding.ivProfileAvatar)
        }
    }

    private var pendingAvatarBase64: String? = null
    private var dialogAvatarImageView: com.google.android.material.imageview.ShapeableImageView? = null

    /**
     * Process picked image: safely subsamples large camera photos, respects EXIF orientation,
     * center-crops to square, resizes to 200x200, compresses to lightweight ~12KB JPEG,
     * caches to internal storage, and returns Base64 data string for Cloud Firebase Realtime Database.
     */
    private fun processAndSaveAvatar(sourceUri: Uri): String? {
        val ctx = context ?: return null
        return try {
            // 1. Inspect image dimensions without loading full bitmap into RAM
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val maxDimension = 360
            var sampleSize = 1
            var w = boundsOptions.outWidth
            var h = boundsOptions.outHeight
            while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            // 2. Decode sampled bitmap safely with RGB_565 (prevents OutOfMemory on high-res camera shots)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val sampledBitmap = ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            // 3. Handle EXIF orientation rotation (camera portrait/landscape)
            val orientation = try {
                ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                    android.media.ExifInterface(input).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (_: Throwable) {
                android.media.ExifInterface.ORIENTATION_NORMAL
            }

            val rotationDegrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            val orientedBitmap = if (rotationDegrees != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
            } else {
                sampledBitmap
            }

            // 4. Center-crop to 1:1 square for perfect circular avatar framing
            val minSide = minOf(orientedBitmap.width, orientedBitmap.height)
            val xOffset = (orientedBitmap.width - minSide) / 2
            val yOffset = (orientedBitmap.height - minSide) / 2
            val squareBitmap = Bitmap.createBitmap(orientedBitmap, xOffset, yOffset, minSide, minSide)

            // 5. Scale to standard 200x200 avatar size
            val targetSize = 200
            val finalBitmap = Bitmap.createScaledBitmap(squareBitmap, targetSize, targetSize, true)

            val baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()

            // 6. Write permanent local file
            val localFile = File(ctx.filesDir, "profile_avatar.jpg")
            localFile.writeBytes(bytes)

            // 7. Base64 encoded payload for Firebase RTDB
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private val galleryLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val ctx = context ?: return@registerForActivityResult
            val base64Data = processAndSaveAvatar(uri)
            pendingAvatarBase64 = base64Data

            val avatarSource = resolveAvatarSource(ctx, base64Data)

            dialogAvatarImageView?.let {
                Glide.with(this@ProfileFragment)
                    .load(avatarSource)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(it)
            }
            Glide.with(this@ProfileFragment)
                .load(avatarSource)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(binding.ivProfileAvatar)

            // Persist the picked photo to the database immediately; it is also kept
            // locally so it survives even if the upload is temporarily offline.
            if (base64Data != null) {
                val repository = ProfileRepository(ctx.applicationContext)
                viewLifecycleOwner.lifecycleScope.launch {
                    val uploadResult = repository.saveAvatar(base64Data)
                    if (!isAdded) return@launch
                    val permissionDenied = repository.lastSyncError?.message
                        ?.contains("permission", ignoreCase = true) == true
                    val message = when {
                        uploadResult.isSuccess -> "Profile photo saved to your account"
                        permissionDenied -> "Photo saved locally • Firebase rules are blocking cloud sync"
                        else -> "Photo saved on this device • will sync when the database is reachable"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeServerPowerToggle(turnOn: Boolean) {
        val ctx = context ?: return
        val ip = ServerPreferences.getIp(ctx)
        val state = if (turnOn) "on" else "off"
        binding.tvAiServerPowerStatus.text = if (turnOn) "Starting AI server on PC..." else "Turning off AI server..."

        lifecycleScope.launch(Dispatchers.IO) {
            var directCallSucceeded = false

            // Save completed monitoring session to History when turning off
            if (!turnOn && CheckupSessionManager.isSessionActive(ctx)) {
                CheckupSessionManager.stopSession(ctx) { session ->
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "Monitoring session #${session.sessionNumber} saved to History", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 1. Immediate LAN call if already running
            try {
                val endpoint = if (turnOn) "resume" else "pause"
                val url = "http://$ip:5000/server/$endpoint"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    directCallSucceeded = true
                }
                response.close()
            } catch (_: Exception) { }

            // 2. Sync toggle state to Firebase so PC watcher launches start_ai_server.bat
            try {
                val db = FirebaseConfig.database()
                db.getReference("system_control").child("ai_server").setValue(state).await()
            } catch (_: Exception) { }

            // 3. If turning on and PC server was not already running, wait for PC watcher to launch start_ai_server.bat
            if (turnOn && !directCallSucceeded) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.tvAiServerPowerStatus.text = "Starting start_ai_server.bat on PC..."
                    }
                }

                // Poll for up to 18 seconds waiting for PC to launch and listen on port 5000
                for (attempt in 1..18) {
                    delay(1000)
                    try {
                        val pingReq = Request.Builder().url("http://$ip:5000/status").build()
                        val pingResp = httpClient.newCall(pingReq).execute()
                        if (pingResp.isSuccessful) {
                            directCallSucceeded = true
                            pingResp.close()
                            break
                        }
                        pingResp.close()
                    } catch (_: Exception) { }
                }
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                isAiServerPaused = !turnOn
                val finalStatus = when {
                    turnOn && directCallSucceeded -> "AI server running"
                    turnOn -> "Signal sent • PC starting AI server"
                    else -> "AI server turned off"
                }
                setSwitchWithoutTriggeringListener(turnOn, finalStatus)
                updateHardwareReadiness()
                checkAiHubLiveStatus()
                Toast.makeText(
                    ctx,
                    if (turnOn) "AI Server turned ON" else "AI Server turned OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupClickListeners() {
        // Edit Avatar on tap
        binding.layoutAvatarContainer.setOnClickListener {
            showSystemSettingsDialog()
        }

        // Edit Profile Pill
        // Edit pill removed per user request

        // AI Server Settings
        binding.btnAiServerSettings.setOnClickListener {
            showAiServerSettingsDialog()
        }

        // AI Server Power Toggle (turn detection server on/off)
        binding.switchAiServerPower.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingPowerSwitch) return@setOnCheckedChangeListener
            val ctx = context ?: return@setOnCheckedChangeListener

            if (!isChecked) {
                // When turning off, ask for confirmation first
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Turn Off AI Server?")
                    .setMessage("Are you sure you want to turn off the AI detection server? Real-time chicken monitoring and automated health alerts will be paused.")
                    .setPositiveButton("Turn Off") { _, _ ->
                        executeServerPowerToggle(false)
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        setSwitchWithoutTriggeringListener(true, "AI server running")
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        setSwitchWithoutTriggeringListener(true, "AI server running")
                    }
                    .show()
            } else {
                // Turning ON
                executeServerPowerToggle(true)
            }
        }

        // System & Profile Settings
        binding.btnSystemSettings.setOnClickListener {
            showSystemSettingsDialog()
        }

        // Notification Preferences
        binding.btnNotificationPreferences.setOnClickListener {
            showNotificationPreferencesDialog()
        }

        // Desktop Power Controls (Shutdown & Turn On)
        binding.btnDesktopPower.setOnClickListener {
            showDesktopPowerDialog()
        }

        // Help & Diagnostics
        binding.btnHelpDiagnostics.setOnClickListener {
            showHelpDiagnosticsDialog()
        }

        // Sign Out
        binding.btnSignOut.setOnClickListener {
            context?.let {
                // Remove the device-local avatar so the next account cannot inherit it.
                File(it.filesDir, "profile_avatar.jpg").delete()
                UserPreferences.clearUser(it)
            }
            authRepository.signOut()
            Toast.makeText(context, "Signed out of PoultrySense", Toast.LENGTH_SHORT).show()

            val intent = Intent(requireActivity(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            requireActivity().finish()
            requireActivity().overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    // ─── 1. Notification Preferences Dialog ───

    private fun showNotificationPreferencesDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogNotificationPreferencesBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Load saved preferences
        val masterEnabled = AppSettingsPreferences.isNotificationsEnabled(ctx)
        dialogBinding.switchMasterNotifications.isChecked = masterEnabled
        dialogBinding.cardSensorAlerts.alpha = if (masterEnabled) 1.0f else 0.5f

        dialogBinding.switchCriticalTemp.isChecked = AppSettingsPreferences.isCriticalTempAlertEnabled(ctx)
        dialogBinding.switchHumidity.isChecked = AppSettingsPreferences.isHumidityAlertEnabled(ctx)
        dialogBinding.switchCameraDisconnect.isChecked = AppSettingsPreferences.isCameraDisconnectAlertEnabled(ctx)
        dialogBinding.switchDailySummary.isChecked = AppSettingsPreferences.isDailySummaryAlertEnabled(ctx)
        dialogBinding.switchAlertSound.isChecked = AppSettingsPreferences.isAlertSoundEnabled(ctx)
        dialogBinding.switchAlertVibrate.isChecked = AppSettingsPreferences.isAlertVibrateEnabled(ctx)

        // SMS Alert Preferences
        dialogBinding.switchSmsAlerts.isChecked = AppSettingsPreferences.isSmsAlertsEnabled(ctx)
        val cachedPhone = AppSettingsPreferences.getAlertPhoneNumber(ctx)
        if (cachedPhone.isNotBlank()) {
            dialogBinding.etAlertPhoneNumber.setText(cachedPhone)
        }

        // Hydrate from Firebase Realtime Database in background if available
        try {
            val smsRef = FirebaseConfig.database().getReference("system_control/sms_alert_settings")
            smsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded || context == null) return
                    val cloudPhone = snapshot.child("farmer_phone_number").getValue(String::class.java)
                    val cloudSms = snapshot.child("sms_alerts_enabled").getValue(Boolean::class.java)
                    if (!cloudPhone.isNullOrBlank() && dialogBinding.etAlertPhoneNumber.text.isNullOrBlank()) {
                        dialogBinding.etAlertPhoneNumber.setText(cloudPhone)
                        AppSettingsPreferences.setAlertPhoneNumber(ctx, cloudPhone)
                    }
                    if (cloudSms != null) {
                        dialogBinding.switchSmsAlerts.isChecked = cloudSms
                        AppSettingsPreferences.setSmsAlertsEnabled(ctx, cloudSms)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        } catch (_: Exception) {}

        // Master switch toggle behavior
        dialogBinding.switchMasterNotifications.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.cardSensorAlerts.alpha = if (isChecked) 1.0f else 0.5f
            dialogBinding.switchCriticalTemp.isEnabled = isChecked
            dialogBinding.switchHumidity.isEnabled = isChecked
            dialogBinding.switchCameraDisconnect.isEnabled = isChecked
            dialogBinding.switchDailySummary.isEnabled = isChecked
            dialogBinding.switchSmsAlerts.isEnabled = isChecked
            dialogBinding.tilAlertPhoneNumber.isEnabled = isChecked
        }

        // Save Button
        dialogBinding.btnSaveNotificationPreferences.setOnClickListener {
            val enabled = dialogBinding.switchMasterNotifications.isChecked
            val smsEnabled = dialogBinding.switchSmsAlerts.isChecked
            val phoneNumber = dialogBinding.etAlertPhoneNumber.text?.toString()?.trim() ?: ""

            AppSettingsPreferences.setNotificationsEnabled(ctx, enabled)
            AppSettingsPreferences.setCriticalTempAlertEnabled(ctx, dialogBinding.switchCriticalTemp.isChecked)
            AppSettingsPreferences.setHumidityAlertEnabled(ctx, dialogBinding.switchHumidity.isChecked)
            AppSettingsPreferences.setCameraDisconnectAlertEnabled(ctx, dialogBinding.switchCameraDisconnect.isChecked)
            AppSettingsPreferences.setDailySummaryAlertEnabled(ctx, dialogBinding.switchDailySummary.isChecked)
            AppSettingsPreferences.setAlertSoundEnabled(ctx, dialogBinding.switchAlertSound.isChecked)
            AppSettingsPreferences.setAlertVibrateEnabled(ctx, dialogBinding.switchAlertVibrate.isChecked)
            AppSettingsPreferences.setSmsAlertsEnabled(ctx, smsEnabled)
            AppSettingsPreferences.setAlertPhoneNumber(ctx, phoneNumber)

            // Push SMS alert settings to Firebase Realtime Database for Python AI server
            try {
                val smsPayload = mapOf(
                    "sms_alerts_enabled" to smsEnabled,
                    "farmer_phone_number" to phoneNumber,
                    "updated_at" to System.currentTimeMillis()
                )
                FirebaseConfig.database().getReference("system_control/sms_alert_settings")
                    .setValue(smsPayload)
            } catch (_: Exception) {}

            Toast.makeText(ctx, "Notification preferences updated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialogBinding.btnCloseNotificationDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─── 2. Desktop Power Controls Dialog ───

    private fun showDesktopPowerDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogDesktopPowerBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val ip = ServerPreferences.getIp(ctx)

        // Live desktop server connection check
        val checkDesktopStatus = {
            dialogBinding.dotDesktopStatus.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_muted))
            dialogBinding.tvDesktopStatusTitle.text = "Checking Desktop Status..."
            dialogBinding.tvDesktopStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            dialogBinding.tvDesktopStatusSubtext.text = "$ip • Port 5000"

            lifecycleScope.launch(Dispatchers.IO) {
                val url = "http://$ip:5000/status"
                val isOnline = try {
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    val ok = response.isSuccessful
                    response.close()
                    ok
                } catch (_: Exception) {
                    false
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (isOnline) {
                        dialogBinding.dotDesktopStatus.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.healthy))
                        dialogBinding.tvDesktopStatusTitle.text = "Desktop AI Server Online"
                        dialogBinding.tvDesktopStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.healthy))
                        dialogBinding.tvDesktopStatusSubtext.text = "Connected at http://$ip:5000"
                    } else {
                        dialogBinding.dotDesktopStatus.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.critical))
                        dialogBinding.tvDesktopStatusTitle.text = "Desktop PC Offline / Standby"
                        dialogBinding.tvDesktopStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                        dialogBinding.tvDesktopStatusSubtext.text = "Host unreachable at $ip:5000"
                    }
                }
            }
        }

        checkDesktopStatus()

        dialogBinding.btnRefreshDesktopStatus.setOnClickListener {
            checkDesktopStatus()
        }

        // Shut Down Desktop Computer
        dialogBinding.btnShutdownDesktop.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Shut Down Desktop Computer?")
                .setMessage("Are you sure you want to shut down your desktop computer ($ip)? The AI vision server and stream will go offline.")
                .setPositiveButton("Shut Down") { _, _ ->
                    dialogBinding.btnShutdownDesktop.isEnabled = false
                    dialogBinding.btnShutdownDesktop.text = "Sending Shutdown Signal..."

                    lifecycleScope.launch(Dispatchers.IO) {
                        // 1. Dispatch cloud shutdown signal to Firebase (received by ESP32)
                        try {
                            val db = FirebaseConfig.database()
                            db.getReference("system_control").updateChildren(mapOf("shutdown" to true, "timestamp" to System.currentTimeMillis()))
                        } catch (_: Exception) {}

                        // 2. Dispatch direct HTTP shutdown signal to Desktop AI Server
                        val shutdownUrl = "http://$ip:5000/power/shutdown"
                        val emptyBody = okhttp3.RequestBody.create(null, ByteArray(0))
                        var success = false
                        var message = ""

                        try {
                            val request = Request.Builder().url(shutdownUrl).post(emptyBody).build()
                            val response = httpClient.newCall(request).execute()
                            success = response.isSuccessful
                            response.close()
                            message = "Shutdown command acknowledged by Desktop PC ($ip)"
                        } catch (e: Exception) {
                            try {
                                val getReq = Request.Builder().url(shutdownUrl).build()
                                val resp = httpClient.newCall(getReq).execute()
                                success = resp.isSuccessful
                                resp.close()
                                message = "Shutdown command sent to Desktop PC ($ip)"
                            } catch (ex: Exception) {
                                message = "Failed to contact desktop at $ip. Ensure server is running."
                            }
                        }

                        withContext(Dispatchers.Main) {
                            dialogBinding.btnShutdownDesktop.isEnabled = true
                            dialogBinding.btnShutdownDesktop.text = "Shut Down Desktop Computer"
                            if (success) {
                                Toast.makeText(ctx, "Desktop Computer shutdown initiated!", Toast.LENGTH_LONG).show()
                                dialogBinding.dotDesktopStatus.backgroundTintList =
                                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.critical))
                                dialogBinding.tvDesktopStatusTitle.text = "Desktop Shutting Down..."
                                dialogBinding.tvDesktopStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                                dialogBinding.tvDesktopStatusSubtext.text = "PC power down in progress"
                            } else {
                                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialogBinding.btnCloseDesktopPowerDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─── 2. Help & Diagnostics Dialog ───

    private fun showHelpDiagnosticsDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogHelpDiagnosticsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val runDiagnostics = {
            dialogBinding.btnRunSelfTest.isEnabled = false
            dialogBinding.btnRunSelfTest.text = "Testing system..."
            dialogBinding.tvAiHubPingText.text = "Pinging hub..."

            val ip = ServerPreferences.getIp(ctx)
            val startTime = System.currentTimeMillis()

            lifecycleScope.launch(Dispatchers.IO) {
                val url = "http://$ip:5000/status"
                var latencyMs = 0L
                val isOnline = try {
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    latencyMs = System.currentTimeMillis() - startTime
                    val ok = response.isSuccessful
                    response.close()
                    ok
                } catch (_: Exception) {
                    false
                }

                withContext(Dispatchers.Main) {
                    dialogBinding.btnRunSelfTest.isEnabled = true
                    dialogBinding.btnRunSelfTest.text = "Run Diagnostics Test"

                    val timeNow = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
                    dialogBinding.tvDiagnosticLastChecked.text = "Tested at $timeNow"

                    if (isOnline) {
                        dialogBinding.dotAiHubStatus.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.healthy))
                        dialogBinding.tvAiHubPingText.text = "Connected (${latencyMs}ms)"
                        dialogBinding.tvAiHubPingText.setTextColor(ContextCompat.getColor(ctx, R.color.healthy))
                    } else {
                        dialogBinding.dotAiHubStatus.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.critical))
                        dialogBinding.tvAiHubPingText.text = "Offline ($ip)"
                        dialogBinding.tvAiHubPingText.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                    }
                }
            }
        }

        // Run self-test immediately on launch
        runDiagnostics()

        dialogBinding.btnRunSelfTest.setOnClickListener {
            runDiagnostics()
        }

        dialogBinding.btnContactSupport.setOnClickListener {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:blueshewakram@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "PoultrySense Farm Support & Diagnostics")
                putExtra(Intent.EXTRA_TEXT, "Hello Engineering,\n\nDiagnostic Log:\nAI Server IP: ${ServerPreferences.getIp(ctx)}\nFarm: ${UserPreferences.getCachedUser(ctx)?.farmName?.ifBlank { "Not set" } ?: "Not set"}\n\nDescription of issue:\n")
            }
            try {
                startActivity(Intent.createChooser(emailIntent, "Send Support Request"))
            } catch (_: Exception) {
                Toast.makeText(ctx, "Support email: blueshewakram@gmail.com", Toast.LENGTH_LONG).show()
            }
        }

        dialogBinding.btnCloseHelpDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─── 4. System Settings (Edit Profile) Dialog ───

    private fun showSystemSettingsDialog() {
        val ctx = context ?: return
        val currentUser = authRepository.currentUser ?: return
        val cachedUser = UserPreferences.getCachedUser(ctx)

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogSystemSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogAvatarImageView = dialogBinding.ivDialogAvatarPreview
        pendingAvatarBase64 = null

        // Pre-fill from the cached/cloud profile. Everything has a real value:
        // no placeholder farm, role or join date is invented.
        val authProfile = cachedUser ?: authRepository.userFromFirebase(currentUser)
        val currentFirstName = authProfile.firstName
        val currentLastName = authProfile.lastName
        val currentFarm = authProfile.farmName
        val dialogAvatarSource = resolveAvatarSource(ctx, authProfile.avatarUrl)

        dialogBinding.etEditFirstName.setText(currentFirstName)
        dialogBinding.etEditLastName.setText(currentLastName)
        dialogBinding.etEditFarmName.setText(currentFarm)

        // Always render the real account creation timestamp, never a hardcoded month.
        val joinDateText = "Member since " +
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(authProfile.createdAt))
        dialogBinding.tvAccountCreatedDate.text = joinDateText

        Glide.with(this@ProfileFragment)
            .load(dialogAvatarSource)
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .into(dialogBinding.ivDialogAvatarPreview)

        // Upload Button & Avatar Click
        dialogBinding.btnPickGalleryPhoto.setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        dialogBinding.ivDialogAvatarPreview.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        dialogBinding.btnCancelSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveProfileSettings.setOnClickListener {
            val newFirst = dialogBinding.etEditFirstName.text?.toString()?.trim().orEmpty()
            val newLast = dialogBinding.etEditLastName.text?.toString()?.trim().orEmpty()
            val newFarm = dialogBinding.etEditFarmName.text?.toString()?.trim().orEmpty()

            if (newFirst.isEmpty()) {
                dialogBinding.tilEditFirstName.error = "First name is required"
                return@setOnClickListener
            }
            dialogBinding.tilEditFirstName.error = null

            val localFile = File(ctx.filesDir, "profile_avatar.jpg")
            val avatarString = pendingAvatarBase64
                ?: authProfile.avatarUrl.takeIf {
                    it.startsWith("data:image") ||
                        (it.startsWith("http") && !it.contains("unsplash.com"))
                }
                ?: if (localFile.exists() && localFile.length() > 0) {
                    try {
                        val bytes = localFile.readBytes()
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } catch (_: Exception) {
                        ""
                    }
                } else {
                    ""
                }

            val updatedUser = authProfile.copy(
                uid = currentUser.uid,
                firstName = newFirst,
                lastName = newLast,
                email = currentUser.email.orEmpty(),
                farmName = newFarm,
                avatarUrl = avatarString
            )

            // Optimistic local update so the screen responds instantly.
            UserPreferences.saveUser(ctx, updatedUser)
            displayUser(updatedUser)
            dialog.dismiss()

            // Authoritative database write. Failure is surfaced (and queued for retry)
            // instead of being silently swallowed, which is how profiles used to get lost.
            val repository = ProfileRepository(ctx.applicationContext)
            viewLifecycleOwner.lifecycleScope.launch {
                val result = repository.saveProfile(currentUser.uid, updatedUser)
                if (!isAdded) return@launch
                val permissionDenied = repository.lastSyncError?.message
                    ?.contains("permission", ignoreCase = true) == true
                val message = when {
                    result.isSuccess -> "Profile saved to your account"
                    permissionDenied -> "Saved locally • Firebase rules are blocking cloud sync"
                    else -> "Saved on this device • will sync when the database is reachable"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }


        dialog.show()
    }

    // ─── 5. AI Server Settings Dialog ───

    private fun showAiServerSettingsDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogAiServerSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Pre-fill current IP
        val currentIp = ServerPreferences.getIp(ctx)
        dialogBinding.etServerIp.setText(currentIp)

        // Asynchronously check if Firebase has a newer reported IP for this PC
        lifecycleScope.launch(Dispatchers.IO) {
            val cloudIp = AiServerScanner.fetchCloudServerIp()
            if (!cloudIp.isNullOrBlank() && cloudIp != currentIp) {
                withContext(Dispatchers.Main) {
                    dialogBinding.layoutDiscoveredBanner.visibility = View.VISIBLE
                    dialogBinding.tvDiscoveredMessage.text = "Edge PC registered at $cloudIp via Cloud"
                    if (dialogBinding.etServerIp.text.isNullOrBlank() || dialogBinding.etServerIp.text.toString() == "192.168.137.1") {
                        dialogBinding.etServerIp.setText(cloudIp)
                    }
                }
            }
        }

        val testClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        // 1-Tap Auto-Scan for AI Hub
        dialogBinding.btnAutoScanHub.setOnClickListener {
            dialogBinding.btnAutoScanHub.isEnabled = false
            dialogBinding.layoutAutoScanning.visibility = View.VISIBLE
            dialogBinding.layoutDiscoveredBanner.visibility = View.GONE
            dialogBinding.tvScanProgress.text = "Searching local Wi-Fi for AI Hub..."

            lifecycleScope.launch {
                val foundIp = AiServerScanner.discoverServer(ctx) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        dialogBinding.tvScanProgress.text = progress
                    }
                }

                dialogBinding.btnAutoScanHub.isEnabled = true
                dialogBinding.layoutAutoScanning.visibility = View.GONE

                if (foundIp != null) {
                    dialogBinding.etServerIp.setText(foundIp)
                    dialogBinding.layoutDiscoveredBanner.visibility = View.VISIBLE
                    dialogBinding.tvDiscoveredMessage.text = "Connected to AI Hub ($foundIp)!"
                    ServerPreferences.saveIp(ctx, foundIp)
                    binding.tvAiServerSubtext.text = "$foundIp • Port 5000"

                    // Update connection status
                    dialogBinding.layoutConnectionStatus.visibility = View.VISIBLE
                    dialogBinding.ivConnectionStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    dialogBinding.ivConnectionStatusIcon.imageTintList =
                        ContextCompat.getColorStateList(ctx, R.color.primary)
                    dialogBinding.tvConnectionStatus.text = "AI Hub Active & Ready"
                    dialogBinding.tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.primary)
                    )

                    Toast.makeText(ctx, "Connected to PoultrySense AI Hub", Toast.LENGTH_SHORT).show()
                    checkAiHubLiveStatus()
                } else {
                    Toast.makeText(
                        ctx,
                        "Could not auto-detect AI Hub. Please ensure start_ai_server.bat is running on your PC.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Test Connection
        dialogBinding.btnTestConnection.setOnClickListener {
            val rawIp = dialogBinding.etServerIp.text?.toString()?.trim() ?: ""
            val ip = rawIp.removePrefix("http://").removePrefix("https://").substringBefore(":").trimEnd('/')
            if (ip.isEmpty()) {
                dialogBinding.tilServerIp.error = "Please enter an IP address"
                return@setOnClickListener
            }
            dialogBinding.tilServerIp.error = null
            dialogBinding.btnTestConnection.isEnabled = false
            dialogBinding.btnTestConnection.text = "Testing…"
            dialogBinding.layoutConnectionStatus.visibility = View.GONE

            lifecycleScope.launch(Dispatchers.IO) {
                val url = "http://$ip:5000/status"
                val success = try {
                    val request = Request.Builder().url(url).build()
                    val response = testClient.newCall(request).execute()
                    val ok = response.isSuccessful
                    response.close()
                    ok
                } catch (e: Exception) {
                    false
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    dialogBinding.btnTestConnection.isEnabled = true
                    dialogBinding.btnTestConnection.text = getString(R.string.test_connection)
                    dialogBinding.layoutConnectionStatus.visibility = View.VISIBLE
                    if (success) {
                        dialogBinding.ivConnectionStatusIcon.setImageResource(R.drawable.ic_check_circle)
                        dialogBinding.ivConnectionStatusIcon.imageTintList =
                            ContextCompat.getColorStateList(ctx, R.color.primary)
                        dialogBinding.tvConnectionStatus.text = getString(R.string.connection_ok)
                        dialogBinding.tvConnectionStatus.setTextColor(
                            ContextCompat.getColor(ctx, R.color.primary)
                        )
                        checkAiHubLiveStatus()
                    } else {
                        dialogBinding.ivConnectionStatusIcon.setImageResource(R.drawable.ic_error)
                        dialogBinding.ivConnectionStatusIcon.imageTintList =
                            ContextCompat.getColorStateList(ctx, R.color.critical)
                        dialogBinding.tvConnectionStatus.text = getString(R.string.connection_failed)
                        dialogBinding.tvConnectionStatus.setTextColor(
                            ContextCompat.getColor(ctx, R.color.critical)
                        )
                    }
                }
            }
        }

        // Save
        dialogBinding.btnSaveServerSettings.setOnClickListener {
            val ip = dialogBinding.etServerIp.text?.toString()?.trim() ?: ""
            if (ip.isEmpty()) {
                dialogBinding.tilServerIp.error = "Please enter an IP address"
                return@setOnClickListener
            }
            dialogBinding.tilServerIp.error = null
            ServerPreferences.saveIp(ctx, ip)
            binding.tvAiServerSubtext.text = "$ip • Port 5000"
            Toast.makeText(ctx, "Server IP saved: $ip", Toast.LENGTH_SHORT).show()
            checkAiHubLiveStatus()
            dialog.dismiss()
        }

        // Cancel
        dialogBinding.btnCancelServerSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        sensorStatusListener?.let {
            FirebaseConfig.database().getReference("sensor_data").child("live").removeEventListener(it)
        }
        sensorStatusListener = null
        aiServerPowerListener?.let {
            FirebaseConfig.database().getReference("system_control").child("ai_server").removeEventListener(it)
        }
        aiServerPowerListener = null
        aiServerIpListener?.let {
            FirebaseConfig.database().getReference("system_control").child("ai_server_ip").removeEventListener(it)
        }
        aiServerIpListener = null
        super.onDestroyView()
        _binding = null
    }
}

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
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.UserPreferences
import com.example.poultrysense.data.models.User
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.DialogAiServerSettingsBinding
import com.example.poultrysense.databinding.DialogHelpDiagnosticsBinding
import com.example.poultrysense.databinding.DialogNotificationPreferencesBinding
import com.example.poultrysense.databinding.DialogSystemSettingsBinding
import com.example.poultrysense.databinding.FragmentProfileBinding
import com.example.poultrysense.ui.auth.LoginActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    }

    private fun loadUserData() {
        val context = context ?: return
        val currentUser = authRepository.currentUser

        // 1. Instant Synchronous Display
        val cachedUser = UserPreferences.getCachedUser(context)
        if (cachedUser != null) {
            displayUser(cachedUser)
        } else if (currentUser != null) {
            val displayName = currentUser.displayName.orEmpty()
            if (displayName.isNotEmpty()) {
                binding.tvProfileName.text = displayName
            }
            binding.tvProfileEmail.text = currentUser.email.orEmpty()
            binding.tvProfileRole.text = "Lead Farm Specialist • Poultry Farm"
        }

        // 2. Background Refresh
        if (currentUser != null) {
            lifecycleScope.launch {
                val result = authRepository.getUserProfile(currentUser.uid)
                result.onSuccess { user ->
                    if (_binding == null) return@onSuccess
                    val localFile = File(context.filesDir, "profile_avatar.jpg")

                    val isStaleOrEmpty = user.avatarUrl.isEmpty() ||
                            user.avatarUrl.startsWith("content://") ||
                            user.avatarUrl.contains("unsplash.com")

                    val effectiveUser = if (isStaleOrEmpty && localFile.exists() && localFile.length() > 0) {
                        try {
                            val bytes = localFile.readBytes()
                            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            launch {
                                authRepository.updateAvatarUrl(currentUser.uid, base64)
                            }
                            user.copy(avatarUrl = base64)
                        } catch (_: Exception) {
                            user.copy(avatarUrl = Uri.fromFile(localFile).toString())
                        }
                    } else if (!user.avatarUrl.isNullOrEmpty() && user.avatarUrl.startsWith("data:image")) {
                        try {
                            val b64 = user.avatarUrl.substringAfter("base64,")
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            if (bytes != null && bytes.isNotEmpty()) {
                                localFile.writeBytes(bytes)
                            }
                        } catch (_: Exception) {}
                        user
                    } else {
                        user
                    }

                    UserPreferences.saveUser(context, effectiveUser)
                    displayUser(effectiveUser)
                }.onFailure {
                    if (_binding == null) return@onFailure
                    if (cachedUser == null) {
                        val fallbackName = currentUser.displayName ?: ""
                        binding.tvProfileName.text = fallbackName
                        binding.tvProfileEmail.text = currentUser.email ?: ""
                    }
                }
            }
        }
    }

    private fun loadOperationalStats() {
        val ctx = context ?: return
        val cachedUser = UserPreferences.getCachedUser(ctx)

        // 1. Total Inspections from CheckupSessionManager
        val inspectionsCount = CheckupSessionManager.getSessionCounter(ctx)
        binding.tvTotalInspections.text = inspectionsCount.toString()

        // 2. System Uptime / Days
        val activeDays = cachedUser?.activeDays ?: 42
        binding.tvSystemUptime.text = "$activeDays Days"

        // 3. Flock Health Index
        val healthIndex = CheckupSessionManager.getLatestHealthIndex(ctx)
        binding.tvFlockHealthRate.text = healthIndex

        // 4. Server IP Info Subtext
        val ip = ServerPreferences.getIp(ctx)
        binding.tvAiServerSubtext.text = "$ip • Port 5000"
    }

    private fun checkAiHubLiveStatus() {
        val ctx = context ?: return
        val ip = ServerPreferences.getIp(ctx)

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
                    binding.dotProfileAiHub.backgroundTintList =
                        ColorStateList.valueOf(android.graphics.Color.parseColor("#00E5FF"))
                    binding.tvProfileAiHubStatus.text = "AI Hub Online"
                } else {
                    binding.dotProfileAiHub.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.tvProfileAiHubStatus.text = "AI Hub Standby"
                }
            }
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

        // 1. Base64 data URI
        if (!rawAvatar.isNullOrEmpty() && rawAvatar.startsWith("data:image")) {
            try {
                val base64Data = rawAvatar.substringAfter("base64,")
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
        binding.tvProfileName.text = user.fullName
        binding.tvProfileRole.text = "${user.role} • ${user.farmName}"
        binding.tvProfileEmail.text = user.email

        val avatarSource = resolveAvatarSource(ctx, user.avatarUrl)

        Glide.with(this@ProfileFragment)
            .load(avatarSource)
            .circleCrop()
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .into(binding.ivProfileAvatar)
    }

    private var pendingAvatarBase64: String? = null
    private var dialogAvatarImageView: com.google.android.material.imageview.ShapeableImageView? = null

    /**
     * Process picked image: scales to 256x256, compresses to ~15KB JPEG,
     * writes to internal storage, and returns Base64 data string for Firebase sync.
     */
    private fun processAndSaveAvatar(sourceUri: Uri): String? {
        val ctx = context ?: return null
        return try {
            val inputStream = ctx.contentResolver.openInputStream(sourceUri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return null

            val size = 256
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()

            val localFile = File(ctx.filesDir, "profile_avatar.jpg")
            localFile.writeBytes(bytes)

            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
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

            // Instantly sync with cachedUser and Firebase so photo is never lost even if user dismisses dialog!
            if (base64Data != null) {
                val cached = UserPreferences.getCachedUser(ctx)
                if (cached != null) {
                    val updated = cached.copy(avatarUrl = base64Data)
                    UserPreferences.saveUser(ctx, updated)
                }
                authRepository.currentUser?.let { user ->
                    lifecycleScope.launch {
                        authRepository.updateAvatarUrl(user.uid, base64Data)
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Edit Avatar on tap
        binding.layoutAvatarContainer.setOnClickListener {
            showSystemSettingsDialog()
        }

        // Edit Profile Pill
        binding.btnEditProfilePill.setOnClickListener {
            showSystemSettingsDialog()
        }

        // AI Server Settings
        binding.btnAiServerSettings.setOnClickListener {
            showAiServerSettingsDialog()
        }

        // System & Profile Settings
        binding.btnSystemSettings.setOnClickListener {
            showSystemSettingsDialog()
        }

        // Notification Preferences
        binding.btnNotificationPreferences.setOnClickListener {
            showNotificationPreferencesDialog()
        }

        // Help & Diagnostics
        binding.btnHelpDiagnostics.setOnClickListener {
            showHelpDiagnosticsDialog()
        }

        // Sign Out
        binding.btnSignOut.setOnClickListener {
            context?.let { UserPreferences.clearUser(it) }
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

        // Master switch toggle behavior
        dialogBinding.switchMasterNotifications.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.cardSensorAlerts.alpha = if (isChecked) 1.0f else 0.5f
            dialogBinding.switchCriticalTemp.isEnabled = isChecked
            dialogBinding.switchHumidity.isEnabled = isChecked
            dialogBinding.switchCameraDisconnect.isEnabled = isChecked
            dialogBinding.switchDailySummary.isEnabled = isChecked
        }

        // Save Button
        dialogBinding.btnSaveNotificationPreferences.setOnClickListener {
            val enabled = dialogBinding.switchMasterNotifications.isChecked
            AppSettingsPreferences.setNotificationsEnabled(ctx, enabled)
            AppSettingsPreferences.setCriticalTempAlertEnabled(ctx, dialogBinding.switchCriticalTemp.isChecked)
            AppSettingsPreferences.setHumidityAlertEnabled(ctx, dialogBinding.switchHumidity.isChecked)
            AppSettingsPreferences.setCameraDisconnectAlertEnabled(ctx, dialogBinding.switchCameraDisconnect.isChecked)
            AppSettingsPreferences.setDailySummaryAlertEnabled(ctx, dialogBinding.switchDailySummary.isChecked)
            AppSettingsPreferences.setAlertSoundEnabled(ctx, dialogBinding.switchAlertSound.isChecked)
            AppSettingsPreferences.setAlertVibrateEnabled(ctx, dialogBinding.switchAlertVibrate.isChecked)

            Toast.makeText(ctx, "Notification preferences updated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialogBinding.btnCloseNotificationDialog.setOnClickListener {
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
                putExtra(Intent.EXTRA_TEXT, "Hello Engineering,\n\nDiagnostic Log:\nAI Server IP: ${ServerPreferences.getIp(ctx)}\nFarm: ${UserPreferences.getCachedUser(ctx)?.farmName ?: "Poultry Farm"}\n\nDescription of issue:\n")
            }
            try {
                startActivity(Intent.createChooser(emailIntent, "Send Support Request"))
            } catch (_: Exception) {
                Toast.makeText(ctx, "Support email: bluesheawakram@gmail.com", Toast.LENGTH_LONG).show()
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

        // Pre-fill user data
        val currentFirstName = cachedUser?.firstName ?: currentUser.displayName?.split(" ")?.firstOrNull() ?: ""
        val currentLastName = cachedUser?.lastName ?: currentUser.displayName?.split(" ")?.drop(1)?.joinToString(" ") ?: ""
        val currentFarm = cachedUser?.farmName ?: "Poultry Farm"
        val dialogAvatarSource = resolveAvatarSource(ctx, cachedUser?.avatarUrl)

        dialogBinding.etEditFirstName.setText(currentFirstName)
        dialogBinding.etEditLastName.setText(currentLastName)
        dialogBinding.etEditFarmName.setText(currentFarm)

        val joinDateText = if (!cachedUser?.formattedDate.isNullOrEmpty()) {
            "Member since ${cachedUser?.formattedDate}"
        } else {
            "Member since August 2026"
        }
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
            val avatarString = pendingAvatarBase64 ?: cachedUser?.avatarUrl?.takeIf {
                it.isNotEmpty() && !it.startsWith("content://") && !it.contains("unsplash.com")
            } ?: if (localFile.exists() && localFile.length() > 0) {
                try {
                    val bytes = localFile.readBytes()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (_: Exception) {
                    Uri.fromFile(localFile).toString()
                }
            } else {
                ""
            }

            val updatedUser = User(
                uid = currentUser.uid,
                firstName = newFirst,
                lastName = newLast,
                email = currentUser.email.orEmpty(),
                farmName = if (newFarm.isNotEmpty()) newFarm else "Poultry Farm",
                role = cachedUser?.role ?: "Lead Farm Specialist",
                avatarUrl = avatarString,
                createdAt = cachedUser?.createdAt ?: System.currentTimeMillis(),
                createdDay = cachedUser?.createdDay ?: 29,
                createdMonth = cachedUser?.createdMonth ?: 8,
                createdMonthName = cachedUser?.createdMonthName ?: "August",
                createdYear = cachedUser?.createdYear ?: 2026,
                formattedDate = cachedUser?.formattedDate ?: "August 29, 2026",
                scansCount = cachedUser?.scansCount ?: 0,
                activeDays = cachedUser?.activeDays ?: 1
            )

            // 1. Instant local update
            UserPreferences.saveUser(ctx, updatedUser)
            displayUser(updatedUser)

            // 2. Cloud update in background
            lifecycleScope.launch {
                authRepository.updateUserProfile(currentUser.uid, updatedUser.toMap())
                if (avatarString.isNotEmpty()) {
                    authRepository.updateAvatarUrl(currentUser.uid, avatarString)
                }
            }

            Toast.makeText(ctx, "Profile and picture updated successfully", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
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
        dialogBinding.etServerIp.setText(ServerPreferences.getIp(ctx))

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
        super.onDestroyView()
        _binding = null
    }
}

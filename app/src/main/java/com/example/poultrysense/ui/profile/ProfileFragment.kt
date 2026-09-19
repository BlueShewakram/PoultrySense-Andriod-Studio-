package com.example.poultrysense.ui.profile

import android.content.Intent
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
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.UserPreferences
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.DialogAiServerSettingsBinding
import com.example.poultrysense.databinding.FragmentProfileBinding
import com.example.poultrysense.ui.auth.LoginActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()

    private val defaultAvatarUrl =
        "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&q=80&w=250"

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
        setupClickListeners()
    }

    private fun loadUserData() {
        val context = context ?: return
        val currentUser = authRepository.currentUser

        // 1. Instant Synchronous Display (0ms delay - no hardcoded delay)
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

        // 2. Background Refresh to keep in sync with cloud
        if (currentUser != null) {
            lifecycleScope.launch {
                val result = authRepository.getUserProfile(currentUser.uid)
                result.onSuccess { user ->
                    if (_binding == null) return@onSuccess
                    UserPreferences.saveUser(context, user)
                    displayUser(user)
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

    private fun displayUser(user: com.example.poultrysense.data.models.User) {
        binding.tvProfileName.text = user.fullName
        binding.tvProfileRole.text = "${user.role} • ${user.farmName}"
        binding.tvProfileEmail.text = user.email

        val avatarToLoad = user.avatarUrl.ifEmpty { defaultAvatarUrl }
        Glide.with(this@ProfileFragment)
            .load(avatarToLoad)
            .circleCrop()
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .into(binding.ivProfileAvatar)
    }

    private var pendingAvatarUri: android.net.Uri? = null
    private var dialogAvatarImageView: com.google.android.material.imageview.ShapeableImageView? = null

    private val galleryLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            pendingAvatarUri = uri
            dialogAvatarImageView?.let {
                Glide.with(this@ProfileFragment)
                    .load(uri)
                    .circleCrop()
                    .into(it)
            }
            // Update main avatar preview immediately
            Glide.with(this@ProfileFragment)
                .load(uri)
                .circleCrop()
                .into(binding.ivProfileAvatar)
        }
    }

    private fun setupClickListeners() {
        // Edit Avatar on tap
        binding.layoutAvatarContainer.setOnClickListener {
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

        binding.btnNotificationPreferences.setOnClickListener {
            Toast.makeText(context, "Notification Preferences", Toast.LENGTH_SHORT).show()
        }

        binding.btnPrivacySecurity.setOnClickListener {
            Toast.makeText(context, "Privacy & Security", Toast.LENGTH_SHORT).show()
        }

        binding.btnHelpDiagnostics.setOnClickListener {
            Toast.makeText(context, "Help & Diagnostics", Toast.LENGTH_SHORT).show()
        }

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

    private fun showSystemSettingsDialog() {
        val ctx = context ?: return
        val currentUser = authRepository.currentUser ?: return
        val cachedUser = UserPreferences.getCachedUser(ctx)

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = com.example.poultrysense.databinding.DialogSystemSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogAvatarImageView = dialogBinding.ivDialogAvatarPreview
        pendingAvatarUri = null

        // Pre-fill user data
        val currentFirstName = cachedUser?.firstName ?: currentUser.displayName?.split(" ")?.firstOrNull() ?: ""
        val currentLastName = cachedUser?.lastName ?: currentUser.displayName?.split(" ")?.drop(1)?.joinToString(" ") ?: ""
        val currentFarm = cachedUser?.farmName ?: "Poultry Farm"
        val currentAvatar = cachedUser?.avatarUrl?.ifEmpty { defaultAvatarUrl } ?: defaultAvatarUrl

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
            .load(currentAvatar)
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

            val avatarString = pendingAvatarUri?.toString() ?: (cachedUser?.avatarUrl ?: "")

            val updatedUser = com.example.poultrysense.data.models.User(
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

            Toast.makeText(ctx, "✅ Profile & picture updated successfully", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

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

        // Test Connection
        dialogBinding.btnTestConnection.setOnClickListener {
            val host = ServerPreferences.normalizeHost(
                dialogBinding.etServerIp.text?.toString().orEmpty()
            )
            if (host.isEmpty()) {
                dialogBinding.tilServerIp.error = "Please enter an IP address"
                return@setOnClickListener
            }
            dialogBinding.etServerIp.setText(host)
            dialogBinding.tilServerIp.error = null
            dialogBinding.btnTestConnection.isEnabled = false
            dialogBinding.btnTestConnection.text = "Testing…"
            dialogBinding.layoutConnectionStatus.visibility = View.GONE

            lifecycleScope.launch(Dispatchers.IO) {
                val url = "http://$host:${ServerPreferences.PORT}/status"
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
            val host = ServerPreferences.normalizeHost(
                dialogBinding.etServerIp.text?.toString().orEmpty()
            )
            if (host.isEmpty()) {
                dialogBinding.tilServerIp.error = "Please enter an IP address"
                return@setOnClickListener
            }
            dialogBinding.tilServerIp.error = null
            ServerPreferences.saveIp(ctx, host)
            Toast.makeText(
                ctx,
                "Server saved: http://$host:${ServerPreferences.PORT}",
                Toast.LENGTH_SHORT
            ).show()
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

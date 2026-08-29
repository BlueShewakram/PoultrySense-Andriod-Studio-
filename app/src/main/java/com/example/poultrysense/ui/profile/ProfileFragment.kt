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
        val currentUser = authRepository.currentUser
        if (currentUser != null) {
            lifecycleScope.launch {
                val result = authRepository.getUserProfile(currentUser.uid)
                result.onSuccess { user ->
                    if (_binding == null) return@onSuccess
                    binding.tvProfileName.text = user.fullName
                    binding.tvProfileRole.text = "${user.role} • ${user.farmName}"
                    binding.tvProfileEmail.text = user.email

                    val avatarToLoad = user.avatarUrl.ifEmpty { defaultAvatarUrl }
                    Glide.with(this@ProfileFragment)
                        .load(avatarToLoad)
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(binding.ivProfileAvatar)
                }.onFailure {
                    if (_binding == null) return@onFailure
                    val fallbackName = currentUser.displayName ?: getString(R.string.profile_name)
                    binding.tvProfileName.text = fallbackName
                    binding.tvProfileEmail.text = currentUser.email ?: ""
                }
            }
        }
    }

    private fun setupClickListeners() {
        // AI Server Settings
        binding.btnAiServerSettings.setOnClickListener {
            showAiServerSettingsDialog()
        }

        // Settings items
        binding.btnSystemSettings.setOnClickListener {
            Toast.makeText(context, "System Settings", Toast.LENGTH_SHORT).show()
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
            val ip = dialogBinding.etServerIp.text?.toString()?.trim() ?: ""
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
            Toast.makeText(ctx, "Server IP saved: $ip", Toast.LENGTH_SHORT).show()
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

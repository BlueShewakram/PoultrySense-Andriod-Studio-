package com.example.poultrysense.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.poultrysense.R
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.FragmentProfileBinding
import com.example.poultrysense.ui.auth.LoginActivity
import kotlinx.coroutines.launch

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

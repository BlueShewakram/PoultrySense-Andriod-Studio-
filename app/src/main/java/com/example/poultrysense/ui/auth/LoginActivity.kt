package com.example.poultrysense.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.ActivityLoginBinding
import com.example.poultrysense.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepository = AuthRepository()
    private var flyingChickenAnimator: FlyingChickenAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: let the scenic image draw under the status bar
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val navHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            val bottomPadding = maxOf(imeHeight, navHeight)
            binding.scrollLogin.setPadding(0, 0, 0, bottomPadding)
            if (imeHeight > 0) {
                val focused = currentFocus
                if (focused == binding.etPassword || focused == binding.tilPassword) {
                    binding.scrollLogin.postDelayed({
                        val targetY = binding.headerSectionLogin.height - 36 + binding.tilPassword.top
                        binding.scrollLogin.smoothScrollTo(0, targetY)
                    }, 100)
                }
            }
            insets
        }

        flyingChickenAnimator = FlyingChickenAnimator(binding.ivFlyingChicken)
        playEntranceAnimation()
        setupValidationListeners()
        setupFocusAndScrollListeners()
        setupClickListeners()
    }

    private fun setupFocusAndScrollListeners() {
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.scrollLogin.postDelayed({
                    val targetY = binding.headerSectionLogin.height - 36 + binding.tilPassword.top
                    binding.scrollLogin.smoothScrollTo(0, targetY)
                }, 200)
            }
        }
        binding.etPassword.setOnClickListener {
            binding.scrollLogin.postDelayed({
                val targetY = binding.headerSectionLogin.height - 36 + binding.tilPassword.top
                binding.scrollLogin.smoothScrollTo(0, targetY)
            }, 200)
        }
        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.scrollLogin.postDelayed({
                    val targetY = binding.headerSectionLogin.height - 36 + binding.tilEmail.top
                    binding.scrollLogin.smoothScrollTo(0, targetY)
                }, 200)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        flyingChickenAnimator?.start()
    }

    override fun onPause() {
        super.onPause()
        flyingChickenAnimator?.stop()
    }

    private fun playEntranceAnimation() {
        val animation = AnimationUtils.loadAnimation(this, R.anim.anim_fade_slide_up)
        binding.cardLogin.startAnimation(animation)
        binding.headerSectionLogin.startAnimation(animation)
    }

    private fun setupValidationListeners() {
        binding.etEmail.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString()?.trim().orEmpty()
            if (binding.tilEmail.error != null && Patterns.EMAIL_ADDRESS.matcher(input).matches()) {
                binding.tilEmail.error = null
            }
        }
        binding.etPassword.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString().orEmpty()
            if (binding.tilPassword.error != null && input.isNotEmpty()) {
                binding.tilPassword.error = null
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            ObjectAnimator.ofFloat(binding.btnLogin, "scaleX", 1f, 0.96f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(binding.btnLogin, "scaleY", 1f, 0.96f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
            handleLogin()
        }

        binding.tvSignUpLink.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun triggerErrorShake(view: View) {
        val shake = AnimationUtils.loadAnimation(this, R.anim.anim_shake)
        view.startAnimation(shake)
        view.requestFocus()
    }

    private fun handleLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        var firstInvalidView: View? = null

        if (email.isEmpty()) {
            binding.tilEmail.error = "Email address is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilEmail
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            if (firstInvalidView == null) firstInvalidView = binding.tilEmail
        } else {
            binding.tilEmail.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = "Password is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilPassword
        } else {
            binding.tilPassword.error = null
        }

        if (firstInvalidView != null) {
            triggerErrorShake(firstInvalidView)
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = authRepository.login(email, password)
            setLoading(false)

            result.onSuccess { user ->
                // Restore the profile from Firebase Realtime Database. The repository
                // re-creates the cloud document if it is missing and hydrates the
                // local avatar file, so signing in after a reinstall restores everything.
                val profileRepository = com.example.poultrysense.data.repository.ProfileRepository(this@LoginActivity)
                val finalUser = profileRepository.loadProfile(user)

                val cloudError = profileRepository.lastSyncError
                val permissionDenied = cloudError?.message
                    ?.contains("permission", ignoreCase = true) == true

                val greeting = finalUser.firstName
                    .ifBlank { user.displayName.orEmpty().substringBefore(" ") }
                    .ifBlank { "farmer" }
                val message = if (permissionDenied) {
                    "Signed in, but Firebase denied database access. Update the Realtime Database rules (database.rules.json)."
                } else {
                    "Welcome back, $greeting!"
                }
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()

                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }.onFailure { error ->
                val errorMessage = when (error) {
                    is FirebaseAuthInvalidUserException -> "No PoultrySense account found with this email."
                    is FirebaseAuthInvalidCredentialsException -> "Incorrect email or password. Please verify and retry."
                    is FirebaseNetworkException -> "Network connection failed. Please check your internet connection."
                    else -> error.localizedMessage ?: "Sign in failed. Please try again."
                }
                Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showForgotPasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_forgot_password, null)
        val tilResetEmail = dialogView.findViewById<TextInputLayout>(R.id.tilResetEmail)
        val etResetEmail = dialogView.findViewById<TextInputEditText>(R.id.etResetEmail)

        val currentEmail = binding.etEmail.text?.toString()?.trim().orEmpty()
        if (currentEmail.isNotEmpty()) {
            etResetEmail.setText(currentEmail)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forgot_password))
            .setMessage(getString(R.string.reset_password_instructions))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.send_reset_link), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val resetEmail = etResetEmail.text?.toString()?.trim().orEmpty()
            if (resetEmail.isEmpty()) {
                tilResetEmail.error = "Please enter your email"
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                tilResetEmail.error = "Enter a valid email address"
                return@setOnClickListener
            }

            dialog.dismiss()
            lifecycleScope.launch {
                val result = authRepository.sendPasswordReset(resetEmail)
                result.onSuccess {
                    Toast.makeText(
                        this@LoginActivity,
                        "Password reset link sent to $resetEmail",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        this@LoginActivity,
                        "Failed to send reset link: ${err.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "" else getString(R.string.sign_in_btn)
        binding.btnLogin.icon = null
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.btnForgotPassword.isEnabled = !loading
    }
}

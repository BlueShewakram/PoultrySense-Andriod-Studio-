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

        flyingChickenAnimator = FlyingChickenAnimator(binding.ivFlyingChicken)
        playEntranceAnimation()
        setupValidationListeners()
        setupClickListeners()
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
                val displayName = user.displayName ?: ""
                val parts = displayName.split(" ")
                val fName = if (parts.isNotEmpty()) parts[0] else ""
                val lName = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
                val initialUser = com.example.poultrysense.data.models.User(
                    uid = user.uid,
                    firstName = fName,
                    lastName = lName,
                    email = user.email ?: "",
                    farmName = "Poultry Farm",
                    role = "Lead Farm Specialist"
                )
                com.example.poultrysense.data.UserPreferences.saveUser(this@LoginActivity, initialUser)

                val greeting = user.displayName?.ifEmpty { null } ?: "Specialist"
                Toast.makeText(this@LoginActivity, "Welcome back, $greeting!", Toast.LENGTH_SHORT).show()

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

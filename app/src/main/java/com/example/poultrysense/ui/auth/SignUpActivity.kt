package com.example.poultrysense.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.poultrysense.R
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.ActivitySignUpBinding
import com.example.poultrysense.ui.MainActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.launch

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private val authRepository = AuthRepository()
    private var flyingChickenAnimator: FlyingChickenAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        flyingChickenAnimator = FlyingChickenAnimator(binding.ivFlyingChickenSignUp)
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
        binding.cardSignUp.startAnimation(animation)
        binding.headerSectionSignUp.startAnimation(animation)
    }

    private fun setupValidationListeners() {
        binding.etFirstName.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString().orEmpty()
            if (binding.tilFirstName.error != null && input.isNotBlank() && input.length >= 2) {
                binding.tilFirstName.error = null
            }
        }

        binding.etLastName.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString().orEmpty()
            if (binding.tilLastName.error != null && input.isNotBlank() && input.length >= 2) {
                binding.tilLastName.error = null
            }
        }

        binding.etEmailSignUp.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString()?.trim().orEmpty()
            if (binding.tilEmailSignUp.error != null && Patterns.EMAIL_ADDRESS.matcher(input).matches()) {
                binding.tilEmailSignUp.error = null
            }
        }

        binding.etPasswordSignUp.doOnTextChanged { text, _, _, _ ->
            val input = text?.toString().orEmpty()
            if (binding.tilPasswordSignUp.error != null && input.length >= 6) {
                binding.tilPasswordSignUp.error = null
            }
        }

        binding.etConfirmPassword.doOnTextChanged { text, _, _, _ ->
            val confirm = text?.toString().orEmpty()
            val pass = binding.etPasswordSignUp.text?.toString().orEmpty()
            if (binding.tilConfirmPassword.error != null && confirm == pass && confirm.isNotEmpty()) {
                binding.tilConfirmPassword.error = null
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnCreateAccount.setOnClickListener {
            // Button subtle bounce effect
            ObjectAnimator.ofFloat(binding.btnCreateAccount, "scaleX", 1f, 0.96f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(binding.btnCreateAccount, "scaleY", 1f, 0.96f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
            handleSignUp()
        }

        binding.tvSignInLink.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun triggerErrorShake(view: View) {
        val shake = AnimationUtils.loadAnimation(this, R.anim.anim_shake)
        view.startAnimation(shake)
        view.requestFocus()
    }

    private fun handleSignUp() {
        val firstName = binding.etFirstName.text?.toString()?.trim().orEmpty()
        val lastName = binding.etLastName.text?.toString()?.trim().orEmpty()
        val farmName = binding.etFarmName.text?.toString()?.trim().orEmpty()
        val email = binding.etEmailSignUp.text?.toString()?.trim().orEmpty()
        val password = binding.etPasswordSignUp.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()
        val termsAccepted = binding.cbTerms.isChecked

        var firstInvalidView: View? = null

        // 1. First Name Validation (Individual)
        if (firstName.isEmpty()) {
            binding.tilFirstName.error = "First name is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilFirstName
        } else if (firstName.length < 2) {
            binding.tilFirstName.error = "At least 2 characters required"
            if (firstInvalidView == null) firstInvalidView = binding.tilFirstName
        } else if (!firstName.matches(Regex("^[a-zA-Z\\s\\-']+$"))) {
            binding.tilFirstName.error = "Alphabet letters only"
            if (firstInvalidView == null) firstInvalidView = binding.tilFirstName
        } else {
            binding.tilFirstName.error = null
        }

        // 2. Last Name Validation (Individual)
        if (lastName.isEmpty()) {
            binding.tilLastName.error = "Last name is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilLastName
        } else if (lastName.length < 2) {
            binding.tilLastName.error = "At least 2 characters required"
            if (firstInvalidView == null) firstInvalidView = binding.tilLastName
        } else if (!lastName.matches(Regex("^[a-zA-Z\\s\\-']+$"))) {
            binding.tilLastName.error = "Alphabet letters only"
            if (firstInvalidView == null) firstInvalidView = binding.tilLastName
        } else {
            binding.tilLastName.error = null
        }

        // 3. Email Validation
        if (email.isEmpty()) {
            binding.tilEmailSignUp.error = "Email address is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilEmailSignUp
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmailSignUp.error = "Enter a valid email address (e.g. name@farm.com)"
            if (firstInvalidView == null) firstInvalidView = binding.tilEmailSignUp
        } else {
            binding.tilEmailSignUp.error = null
        }

        // 4. Password Validation
        if (password.isEmpty()) {
            binding.tilPasswordSignUp.error = "Password is required"
            if (firstInvalidView == null) firstInvalidView = binding.tilPasswordSignUp
        } else if (password.length < 6) {
            binding.tilPasswordSignUp.error = "Password must be at least 6 characters"
            if (firstInvalidView == null) firstInvalidView = binding.tilPasswordSignUp
        } else {
            binding.tilPasswordSignUp.error = null
        }

        // 5. Confirm Password Validation
        if (confirmPassword.isEmpty()) {
            binding.tilConfirmPassword.error = "Please confirm your password"
            if (firstInvalidView == null) firstInvalidView = binding.tilConfirmPassword
        } else if (password != confirmPassword) {
            binding.tilConfirmPassword.error = "Passwords do not match"
            if (firstInvalidView == null) firstInvalidView = binding.tilConfirmPassword
        } else {
            binding.tilConfirmPassword.error = null
        }

        // 6. Terms Validation
        if (!termsAccepted) {
            Snackbar.make(binding.root, "Please accept the Terms of Service & Privacy Policy", Snackbar.LENGTH_SHORT).show()
            if (firstInvalidView == null) firstInvalidView = binding.cbTerms
        }

        if (firstInvalidView != null) {
            triggerErrorShake(firstInvalidView)
            binding.scrollSignUp.smoothScrollTo(0, firstInvalidView.top)
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = authRepository.signUp(
                firstName = firstName,
                lastName = lastName,
                email = email,
                password = password,
                farmName = farmName
            )

            setLoading(false)

            result.onSuccess { user ->
                com.example.poultrysense.data.UserPreferences.saveUser(this@SignUpActivity, user)
                Toast.makeText(
                    this@SignUpActivity,
                    "Account created! Welcome, ${user.firstName}.",
                    Toast.LENGTH_SHORT
                ).show()

                val intent = Intent(this@SignUpActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }.onFailure { error ->
                val errorMessage = when (error) {
                    is FirebaseAuthUserCollisionException -> "An account with this email address already exists."
                    is FirebaseAuthWeakPasswordException -> "The password provided is too weak."
                    is FirebaseAuthInvalidCredentialsException -> "The email address is formatted incorrectly."
                    is FirebaseNetworkException -> "Network error. Please check your internet connection."
                    else -> error.localizedMessage ?: "Sign up failed. Please try again."
                }
                Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnCreateAccount.isEnabled = !loading
        binding.btnCreateAccount.text = if (loading) "" else getString(R.string.create_account_btn)
        binding.btnCreateAccount.icon = null
        binding.progressBarSignUp.visibility = if (loading) View.VISIBLE else View.GONE

        binding.etFirstName.isEnabled = !loading
        binding.etLastName.isEnabled = !loading
        binding.etFarmName.isEnabled = !loading
        binding.etEmailSignUp.isEnabled = !loading
        binding.etPasswordSignUp.isEnabled = !loading
        binding.etConfirmPassword.isEnabled = !loading
        binding.cbTerms.isEnabled = !loading
    }
}

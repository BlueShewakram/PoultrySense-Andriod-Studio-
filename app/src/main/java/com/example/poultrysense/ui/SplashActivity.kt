package com.example.poultrysense.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.ActivitySplashBinding
import com.example.poultrysense.ui.auth.LoginActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideInitialViews()
        startCinematicSequence()
    }

    private fun hideInitialViews() {
        binding.logoContainer.scaleX = 0.3f
        binding.logoContainer.scaleY = 0.3f
        binding.logoContainer.alpha = 0f

        binding.tvSplashTitle.alpha = 0f
        binding.tvSplashTitle.translationY = 24f

        binding.tvSplashTagline.alpha = 0f
        binding.tvSplashTagline.translationY = 16f

        binding.layoutStatusPill.alpha = 0f
        binding.layoutStatusPill.scaleX = 0.8f
        binding.layoutStatusPill.scaleY = 0.8f

        binding.pulseRing1.alpha = 0f
        binding.pulseRing2.alpha = 0f
    }

    private fun startCinematicSequence() {
        // 1. Sonar Radar Pulse Loop
        startSonarPulse(binding.pulseRing1, 0)
        startSonarPulse(binding.pulseRing2, 600)

        // 2. Logo Spring Entrance
        binding.logoContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()

        // 3. Title & Tagline Slide Up
        binding.tvSplashTitle.animate()
            .alpha(1.0f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.tvSplashTagline.animate()
            .alpha(1.0f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(380)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 4. Status Beacon Pop-in
        binding.layoutStatusPill.animate()
            .alpha(1.0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(400)
            .setStartDelay(500)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()

        // 5. Status Dot Pulse
        val dotPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat("scaleX", 0.8f, 1.3f),
            PropertyValuesHolder.ofFloat("scaleY", 0.8f, 1.3f),
            PropertyValuesHolder.ofFloat("alpha", 0.6f, 1.0f)
        ).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 600
        }
        dotPulse.start()

        // 6. Seamless Navigation to Next Screen
        handler.postDelayed({
            val authRepository = AuthRepository()
            val targetActivity = if (authRepository.isUserLoggedIn) {
                MainActivity::class.java
            } else {
                LoginActivity::class.java
            }
            startActivity(Intent(this, targetActivity))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1350)
    }

    private fun startSonarPulse(ringView: View, delayMs: Long) {
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 0.8f, 2.2f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 0.8f, 2.2f)
        val alpha = PropertyValuesHolder.ofFloat("alpha", 0.8f, 0.0f)

        ObjectAnimator.ofPropertyValuesHolder(ringView, scaleX, scaleY, alpha).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            startDelay = delayMs
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

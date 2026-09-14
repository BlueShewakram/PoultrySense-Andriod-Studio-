package com.example.poultrysense.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.ActivitySplashBinding
import com.example.poultrysense.ui.auth.LoginActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge full-bleed obsidian canvas
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialState()
        runBrandSequence()
    }

    private fun setupInitialState() {
        binding.flLogoFrame.alpha = 0f
        binding.flLogoFrame.scaleX = 0.88f
        binding.flLogoFrame.scaleY = 0.88f

        binding.viewAmbientGlow.alpha = 0f
        binding.viewAmbientGlow.scaleX = 0.7f
        binding.viewAmbientGlow.scaleY = 0.7f

        binding.tvBrandTitle.alpha = 0f
        binding.tvBrandTitle.translationY = 16f

        binding.tvBrandSubtitle.alpha = 0f
        binding.tvBrandSubtitle.translationY = 12f

        binding.layoutFooter.alpha = 0f
    }

    private fun runBrandSequence() {
        val decelerator = DecelerateInterpolator(1.8f)

        // 1. Logo smooth scale & settle
        binding.flLogoFrame.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(decelerator)
            .start()

        // 2. Ambient radial pulse behind logo
        binding.viewAmbientGlow.animate()
            .alpha(0.5f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(750)
            .setInterpolator(decelerator)
            .start()

        // 3. Typography reveals
        binding.tvBrandTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(160)
            .setInterpolator(decelerator)
            .start()

        binding.tvBrandSubtitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(230)
            .setInterpolator(decelerator)
            .start()

        binding.layoutFooter.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(320)
            .setInterpolator(decelerator)
            .start()

        // Subtle breathing status dot
        val dotPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat("scaleX", 0.85f, 1.25f),
            PropertyValuesHolder.ofFloat("scaleY", 0.85f, 1.25f),
            PropertyValuesHolder.ofFloat("alpha", 0.6f, 1.0f)
        ).apply {
            duration = 600
            repeatCount = 2
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 350
        }
        dotPulse.start()

        // 4. Smooth Exit Transition at 1050ms
        handler.postDelayed({
            // Gentle expansion fade-out
            binding.flLogoFrame.animate()
                .alpha(0f)
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(240)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            binding.tvBrandTitle.animate()
                .alpha(0f)
                .setDuration(200)
                .start()

            binding.tvBrandSubtitle.animate()
                .alpha(0f)
                .setDuration(200)
                .start()

            binding.viewAmbientGlow.animate()
                .alpha(0f)
                .scaleX(1.35f)
                .scaleY(1.35f)
                .setDuration(250)
                .start()

            binding.layoutFooter.animate()
                .alpha(0f)
                .setDuration(180)
                .start()

            // Navigate
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
            }, 220)

        }, 1050)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

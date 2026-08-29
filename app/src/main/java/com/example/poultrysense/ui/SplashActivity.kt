package com.example.poultrysense.ui

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
import androidx.core.view.WindowCompat
import com.example.poultrysense.data.repository.AuthRepository
import com.example.poultrysense.databinding.ActivitySplashBinding
import com.example.poultrysense.ui.auth.LoginActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge for seamless full-bleed artwork
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideInitialViews()
        startCinematicSequence()
    }

    private fun hideInitialViews() {
        binding.ivSplashArtwork.scaleX = 1.03f
        binding.ivSplashArtwork.scaleY = 1.03f

        binding.viewTreeShimmer.alpha = 0f

        binding.layoutStatusPill.alpha = 0f
        binding.layoutStatusPill.scaleX = 0.8f
        binding.layoutStatusPill.scaleY = 0.8f
        binding.layoutStatusPill.translationY = 20f

        binding.layoutFooterBanner.alpha = 0f
        binding.layoutFooterBanner.translationY = 25f
    }

    private fun startCinematicSequence() {
        // 1. Slow cinematic camera settle on the artwork
        binding.ivSplashArtwork.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(2200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 2. Subtle warm golden aura shimmer on the telemetry tree
        startAuraShimmer(binding.viewTreeShimmer)

        // 3. Status Pill Pop-in with Animated Status Progression
        binding.layoutStatusPill.animate()
            .alpha(1.0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(350)
            .setInterpolator(OvershootInterpolator(2.0f))
            .start()

        // Status Dot Breathing
        val dotPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat("scaleX", 0.8f, 1.35f),
            PropertyValuesHolder.ofFloat("scaleY", 0.8f, 1.35f),
            PropertyValuesHolder.ofFloat("alpha", 0.6f, 1.0f)
        ).apply {
            duration = 650
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 400
        }
        dotPulse.start()

        // Dynamic multi-stage status text simulation
        handler.postDelayed({
            binding.tvStatusText.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    binding.tvStatusText.text = "Calibrating Neural Vision..."
                    binding.tvStatusText.animate().alpha(1f).setDuration(160).start()
                }.start()
        }, 750)

        handler.postDelayed({
            binding.tvStatusText.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    binding.tvStatusText.text = "Flock Analysis Online ✓"
                    binding.tvStatusText.animate().alpha(1f).setDuration(160).start()
                    binding.layoutStatusPill.animate()
                        .scaleX(1.06f)
                        .scaleY(1.06f)
                        .setDuration(120)
                        .withEndAction {
                            binding.layoutStatusPill.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }.start()
                }.start()
        }, 1450)

        // 4. Wooden Footer Banner Glide Up
        binding.layoutFooterBanner.animate()
            .alpha(1.0f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(500)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 5. Smooth Hand-off Navigation
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
        }, 2200)
    }

    private fun startAuraShimmer(ringView: View) {
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 0.8f, 1.8f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 0.8f, 1.8f)
        val alpha = PropertyValuesHolder.ofFloat("alpha", 0.6f, 0.0f)

        ObjectAnimator.ofPropertyValuesHolder(ringView, scaleX, scaleY, alpha).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

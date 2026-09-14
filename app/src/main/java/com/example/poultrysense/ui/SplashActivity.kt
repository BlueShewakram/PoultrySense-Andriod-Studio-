package com.example.poultrysense.ui

import android.animation.ObjectAnimator
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

        // Edge-to-edge canvas with light system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialState()
        runBrandSequence()
    }

    private fun setupInitialState() {
        binding.flLogoFrame.alpha = 0f
        binding.flLogoFrame.scaleX = 0.82f
        binding.flLogoFrame.scaleY = 0.82f
        binding.flLogoFrame.translationY = -12f

        binding.viewAmbientGlow.alpha = 0f
        binding.viewAmbientGlow.scaleX = 0.6f
        binding.viewAmbientGlow.scaleY = 0.6f

        binding.tvBrandTitle.alpha = 0f
        binding.tvBrandTitle.translationY = 16f

        binding.tvBrandSubtitle.alpha = 0f
        binding.tvBrandSubtitle.translationY = 12f

        binding.layoutFooter.alpha = 0f
        binding.progressSplash.progress = 0
    }

    private fun runBrandSequence() {
        val decelerator = DecelerateInterpolator(1.8f)

        // 1. 3D Mascot smooth scale, settle and float down
        binding.flLogoFrame.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(decelerator)
            .start()

        // 2. Ambient radial aura expansion behind mascot
        binding.viewAmbientGlow.animate()
            .alpha(0.85f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(800)
            .setInterpolator(decelerator)
            .start()

        // 3. Modern Typography reveals
        binding.tvBrandTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(180)
            .setInterpolator(decelerator)
            .start()

        binding.tvBrandSubtitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(260)
            .setInterpolator(decelerator)
            .start()

        // 4. Modern progress footer reveal & animated loading fill
        binding.layoutFooter.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(320)
            .setInterpolator(decelerator)
            .start()

        val progressAnim = ObjectAnimator.ofInt(binding.progressSplash, "progress", 0, 100).apply {
            duration = 950
            interpolator = DecelerateInterpolator(1.5f)
            startDelay = 250
        }
        progressAnim.start()

        // 5. Smooth Exit Transition to Login or Main
        handler.postDelayed({
            // Gentle expansion fade-out
            binding.flLogoFrame.animate()
                .alpha(0f)
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(260)
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
                .scaleX(1.4f)
                .scaleY(1.4f)
                .setDuration(280)
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
            }, 240)

        }, 1300)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

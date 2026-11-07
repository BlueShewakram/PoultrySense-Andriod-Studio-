package com.example.poultrysense.ui.auth

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Handles the animated flight of the chicken across the sky in auth screens.
 * Animates translation across X and curved Y with gentle wing bobbing and angle tilts.
 */
class FlyingChickenAnimator(private val chickenView: View) {

    private var flightAnimator: ValueAnimator? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        chickenView.post {
            setupAndRunLoop()
        }
    }

    private fun setupAndRunLoop() {
        if (!isRunning) return

        val parentView = chickenView.parent as? View ?: return
        val parentWidth = parentView.width.toFloat().coerceAtLeast(800f)
        val parentHeight = parentView.height.toFloat().coerceAtLeast(500f)

        val startX = -160f
        val endX = parentWidth + 160f

        val startY = parentHeight * 0.42f
        val endY = parentHeight * 0.08f

        flightAnimator?.cancel()
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6500L
            interpolator = LinearInterpolator()

            addUpdateListener { va ->
                val progress = va.animatedValue as Float

                // Horizontal glide
                chickenView.translationX = startX + (endX - startX) * progress

                // Curved vertical ascent with flapping bob
                val baseCurvedY = startY + (endY - startY) * progress
                val wingBob = sin(progress * Math.PI * 6).toFloat() * 14f
                chickenView.translationY = baseCurvedY + wingBob

                // Flight tilt rotation
                val tilt = -12f + sin(progress * Math.PI * 4).toFloat() * 4f
                chickenView.rotation = tilt

                // Fade in at start and fade out at boundary
                chickenView.alpha = when {
                    progress < 0.12f -> progress / 0.12f
                    progress > 0.88f -> (1f - progress) / 0.12f
                    else -> 1f
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isRunning) {
                        chickenView.postDelayed({
                            if (isRunning) setupAndRunLoop()
                        }, 1200L) // Gentle pause before next flight
                    }
                }
            })

            start()
        }
    }

    fun stop() {
        isRunning = false
        flightAnimator?.cancel()
        flightAnimator = null
    }
}

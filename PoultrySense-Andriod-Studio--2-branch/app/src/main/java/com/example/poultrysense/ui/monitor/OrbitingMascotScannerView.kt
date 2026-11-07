package com.example.poultrysense.ui.monitor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.poultrysense.R

/**
 * Ultra-Clean Modern 3D AI Chicken Mascot Scanner View.
 * - Displays ONLY the cute 3D cartoon chicken and searching magnifying glass (zero words/text, zero light bulbs/sparkles).
 * - Extra-large size for high visual impact.
 * - Appears while waiting for the AI stream signal, scanning back & forth.
 * - Smoothly shrinks and fades away as soon as the video signal is received.
 */
class OrbitingMascotScannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val mascotContainer: FrameLayout
    private val chickenImageView: ImageView
    private val magnifierImageView: ImageView       

    private var searchAnimatorX: ObjectAnimator? = null
    private var searchAnimatorY: ObjectAnimator? = null
    private var searchAnimatorRot: ObjectAnimator? = null
    private var searchAnimatorScaleX: ObjectAnimator? = null
    private var searchAnimatorScaleY: ObjectAnimator? = null
    private var chickenBobAnimator: ObjectAnimator? = null
    private var autoDismissRunnable: Runnable? = null
    private var isDismissing = false
    private val handler = Handler(Looper.getMainLooper())

    private val density = context.resources.displayMetrics.density

    var onDismissListener: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false

        // Centered Floating Mascot Container (No background boxes, no words, no bulbs)
        mascotContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            val size = (260 * density).toInt()
            layoutParams = LayoutParams(size, size, Gravity.CENTER)
        }
        addView(mascotContainer)

        // 1. Cute 3D Smiling Chicken (Large clean high-res asset)
        chickenImageView = ImageView(context).apply {
            val size = (185 * density).toInt()
            layoutParams = LayoutParams(size, size, Gravity.CENTER)
            setImageResource(R.drawable.img_mascot_chicken_clean)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = 6f * density
        }
        mascotContainer.addView(chickenImageView)

        // 2. Glossy 3D Searching Magnifying Glass (Enlarged)
        magnifierImageView = ImageView(context).apply {
            val size = (110 * density).toInt()
            layoutParams = LayoutParams(size, size, Gravity.CENTER)
            setImageResource(R.drawable.img_mascot_magnifier_clean)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = 14f * density
        }
        mascotContainer.addView(magnifierImageView)

        // Tap to dismiss early
        setOnClickListener {
            dismissPopup()
        }
    }

    /**
     * Shows the mascot in center while waiting for AI signal,
     * continuously scanning until signal arrives.
     */
    fun showSearchingUntilSignal() {
        if (visibility == View.VISIBLE && alpha > 0f && !isDismissing) {
            return
        }
        isDismissing = false
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null

        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.25f
        scaleY = 0.25f

        // Spring Pop-In
        val popInX = ObjectAnimator.ofFloat(this, "scaleX", 0.25f, 1.06f, 1.0f)
        val popInY = ObjectAnimator.ofFloat(this, "scaleY", 0.25f, 1.06f, 1.0f)
        val fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(popInX, popInY, fadeIn)
            duration = 420L
            interpolator = OvershootInterpolator(1.3f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startSearchAnimation()
                }
            })
            start()
        }
    }

    /**
     * Shows the clean pop-up in center with smooth spring entrance,
     * performs the search animation, and smoothly disappears after durationMs.
     */
    fun showPopupAndScan(durationMs: Long = 2600L) {
        showSearchingUntilSignal()
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            dismissPopup()
        }
        handler.postDelayed(autoDismissRunnable!!, durationMs)
    }

    /**
     * Called when the live AI video stream signal is successfully received.
     */
    fun hideWhenSignalReceived() {
        if (visibility == View.VISIBLE && !isDismissing) {
            dismissPopup()
        }
    }

    /**
     * Fluid back-and-forth & up-and-down searching scan across the chicken.
     */
    private fun startSearchAnimation() {
        stopSearchAnimation()

        // Horizontal sweeping scan (X)
        searchAnimatorX = ObjectAnimator.ofFloat(
            magnifierImageView,
            "translationX",
            -50f * density,
            50f * density
        ).apply {
            duration = 880L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Vertical sweeping scan (Y)
        searchAnimatorY = ObjectAnimator.ofFloat(
            magnifierImageView,
            "translationY",
            -38f * density,
            36f * density
        ).apply {
            duration = 1250L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Natural angular tilt banking
        searchAnimatorRot = ObjectAnimator.ofFloat(
            magnifierImageView,
            "rotation",
            -18f,
            18f
        ).apply {
            duration = 980L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Subtle optical focus pulse
        searchAnimatorScaleX = ObjectAnimator.ofFloat(
            magnifierImageView,
            "scaleX",
            0.96f,
            1.15f
        ).apply {
            duration = 780L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        searchAnimatorScaleY = ObjectAnimator.ofFloat(
            magnifierImageView,
            "scaleY",
            0.96f,
            1.15f
        ).apply {
            duration = 780L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Gentle breathing bob on chicken
        chickenBobAnimator = ObjectAnimator.ofFloat(
            chickenImageView,
            "translationY",
            -4.5f * density,
            4.5f * density
        ).apply {
            duration = 680L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopSearchAnimation() {
        searchAnimatorX?.cancel()
        searchAnimatorX = null
        searchAnimatorY?.cancel()
        searchAnimatorY = null
        searchAnimatorRot?.cancel()
        searchAnimatorRot = null
        searchAnimatorScaleX?.cancel()
        searchAnimatorScaleX = null
        searchAnimatorScaleY?.cancel()
        searchAnimatorScaleY = null
        chickenBobAnimator?.cancel()
        chickenBobAnimator = null
    }

    /**
     * Smoothly shrink and fade away.
     */
    fun dismissPopup() {
        if (isDismissing || visibility != View.VISIBLE) return
        isDismissing = true

        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null

        val popOutX = ObjectAnimator.ofFloat(this, "scaleX", scaleX, 0.6f)
        val popOutY = ObjectAnimator.ofFloat(this, "scaleY", scaleY, 0.6f)
        val fadeOut = ObjectAnimator.ofFloat(this, "alpha", alpha, 0f)

        AnimatorSet().apply {
            playTogether(popOutX, popOutY, fadeOut)
            duration = 300L
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    isDismissing = false
                    stopSearchAnimation()
                    onDismissListener?.invoke()
                }
            })
            start()
        }
    }

    fun startAllAnimations() {
        showSearchingUntilSignal()
    }

    fun stopAllAnimations() {
        stopSearchAnimation()
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimations()
    }
}

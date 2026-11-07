package com.example.poultrysense.ui.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.poultrysense.data.models.DetectionItem

/**
 * Draws AI bounding boxes on top of the smooth raw camera preview with
 * 60 FPS temporal interpolation (Lerp) and fade transitions.
 *
 * Even though the server publishes AI detections at 2-3 Hz, this overlay
 * continuously interpolates box coordinates at display refresh rate,
 * eliminating jitter and teleporting boxes while keeping video at full camera FPS.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class TrackedBox(
        val key: Int,
        var currentRect: RectF,
        var targetRect: RectF,
        var label: String,
        var color: Int,
        var currentAlpha: Float = 0f,
        var targetAlpha: Float = 1f,
        var isAlive: Boolean = true
    )

    private val activeTracks = mutableMapOf<Int, TrackedBox>()
    private var sourceWidth = 640
    private var sourceHeight = 480
    private var lastFrameTimeMs: Long = 0L

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD
        )
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 15, 23, 42)
    }

    fun setDetections(items: List<DetectionItem>?, frameWidth: Int, frameHeight: Int) {
        sourceWidth = frameWidth.coerceAtLeast(1)
        sourceHeight = frameHeight.coerceAtLeast(1)

        val seenKeys = mutableSetOf<Int>()

        items.orEmpty().forEachIndexed { index, item ->
            val bbox = item.bbox
            if (bbox == null || bbox.size < 4) return@forEachIndexed
            val left = bbox[0].toFloat()
            val top = bbox[1].toFloat()
            val right = bbox[2].toFloat()
            val bottom = bbox[3].toFloat()
            if (right <= left || bottom <= top) return@forEachIndexed

            val trackId = item.trackId ?: 0
            // Unique key for tracking; fallback to negative index if trackId <= 0
            val key = if (trackId > 0) trackId else -(index + 1000)
            seenKeys.add(key)

            val tier = item.anomalyTier ?: "Normal"
            val label = buildString {
                if (trackId > 0) append('#').append(trackId) else append("CHICKEN")
                when {
                    item.behavior?.equals("recumbent", ignoreCase = true) == true -> append(" RECUMBENT")
                    tier.equals("High Concern", ignoreCase = true) -> append(" ALERT")
                    tier.equals("Suspicious", ignoreCase = true) -> append(" ATTENTION")
                    tier.equals("Monitor", ignoreCase = true) -> append(" WATCH")
                    else -> item.behavior?.takeIf { it.isNotBlank() }?.let {
                        append(' ').append(it.uppercase(java.util.Locale.getDefault()))
                    }
                }
                if (item.geminiVerified == true) append(" \u2713")
            }
            val color = colorForTier(tier)
            val newTarget = RectF(left, top, right, bottom)

            val existing = activeTracks[key]
            if (existing != null) {
                existing.targetRect.set(newTarget)
                existing.label = label
                existing.color = color
                existing.targetAlpha = 1f
                existing.isAlive = true
            } else {
                // New detection: smooth fade in from initial position
                activeTracks[key] = TrackedBox(
                    key = key,
                    currentRect = RectF(newTarget),
                    targetRect = newTarget,
                    label = label,
                    color = color,
                    currentAlpha = 0f,
                    targetAlpha = 1f,
                    isAlive = true
                )
            }
        }

        // Mark missing tracks to fade out smoothly
        activeTracks.values.forEach { track ->
            if (track.key !in seenKeys) {
                track.targetAlpha = 0f
                track.isAlive = false
            }
        }

        lastFrameTimeMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun clear() {
        if (activeTracks.isNotEmpty()) {
            activeTracks.clear()
            invalidate()
        }
    }

    private fun colorForTier(tier: String): Int = when (tier) {
        "High Concern" -> Color.parseColor("#FF4646")
        "Suspicious" -> Color.parseColor("#FF8C00")
        "Monitor" -> Color.parseColor("#00D7FF")
        else -> Color.parseColor("#00E676")
    }

    private fun lerp(current: Float, target: Float, factor: Float): Float {
        return current + (target - current) * factor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeTracks.isEmpty() || width == 0 || height == 0) return

        val now = SystemClock.uptimeMillis()
        val dtSec = if (lastFrameTimeMs > 0L) {
            ((now - lastFrameTimeMs).coerceIn(1L, 100L)) / 1000f
        } else {
            0.016f
        }
        lastFrameTimeMs = now

        // Exponential smoothing factor: frame-rate independent (~12 / sec convergence)
        val posLerpFactor = (1.0f - Math.exp((-14.0 * dtSec).toDouble())).toFloat().coerceIn(0.15f, 0.65f)
        val alphaLerpFactor = (1.0f - Math.exp((-10.0 * dtSec).toDouble())).toFloat().coerceIn(0.15f, 0.60f)

        // Center-crop projection transform matching ImageView
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        val mapped = RectF()
        val density = resources.displayMetrics.density
        var needsAnotherFrame = false

        val iterator = activeTracks.values.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()

            // Smooth position interpolation
            track.currentRect.left = lerp(track.currentRect.left, track.targetRect.left, posLerpFactor)
            track.currentRect.top = lerp(track.currentRect.top, track.targetRect.top, posLerpFactor)
            track.currentRect.right = lerp(track.currentRect.right, track.targetRect.right, posLerpFactor)
            track.currentRect.bottom = lerp(track.currentRect.bottom, track.targetRect.bottom, posLerpFactor)

            // Smooth opacity interpolation
            track.currentAlpha = lerp(track.currentAlpha, track.targetAlpha, alphaLerpFactor)

            // Prune dead tracks once fully faded out
            if (!track.isAlive && track.currentAlpha < 0.03f) {
                iterator.remove()
                continue
            }

            // Check if animation is still settling
            val posDiff = Math.abs(track.currentRect.left - track.targetRect.left) +
                          Math.abs(track.currentRect.top - track.targetRect.top)
            val alphaDiff = Math.abs(track.currentAlpha - track.targetAlpha)
            if (posDiff > 0.5f || alphaDiff > 0.01f) {
                needsAnotherFrame = true
            }

            if (track.currentAlpha <= 0.01f) continue

            val alphaInt = (track.currentAlpha.coerceIn(0f, 1f) * 255).toInt()

            mapped.set(
                track.currentRect.left * scale + offsetX,
                track.currentRect.top * scale + offsetY,
                track.currentRect.right * scale + offsetX,
                track.currentRect.bottom * scale + offsetY
            )

            // 1. Draw rounded bounding box
            boxPaint.color = track.color
            boxPaint.alpha = alphaInt
            canvas.drawRoundRect(mapped, 6f * density, 6f * density, boxPaint)

            // 2. Measure & draw label banner
            textPaint.alpha = alphaInt
            val textWidth = textPaint.measureText(track.label)
            val padding = 6f * density
            val labelHeight = textPaint.textSize + padding
            val labelLeft = mapped.left.coerceAtLeast(0f)
            val labelTop = (mapped.top - labelHeight).coerceAtLeast(0f)
            val labelRight = (labelLeft + textWidth + padding * 2).coerceAtMost(width.toFloat())
            val labelBottom = (labelTop + labelHeight).coerceAtMost(height.toFloat())

            labelBackgroundPaint.alpha = (track.currentAlpha.coerceIn(0f, 1f) * 180).toInt()
            canvas.drawRoundRect(
                RectF(labelLeft, labelTop, labelRight, labelBottom),
                6f * density, 6f * density, labelBackgroundPaint
            )

            canvas.drawText(
                track.label,
                labelLeft + padding,
                labelBottom - padding,
                textPaint
            )
        }

        if (needsAnotherFrame) {
            postInvalidateOnAnimation()
        }
    }
}

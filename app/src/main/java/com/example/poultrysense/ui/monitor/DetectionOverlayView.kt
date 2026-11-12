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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Draws AI bounding boxes on top of the smooth raw camera preview.
 *
 * The server publishes boxes at the detector rate (~20-30 Hz) while the screen
 * refreshes at 60 Hz. Snapping the box on every payload makes it stutter; a
 * plain lerp makes it trail the bird. So each track keeps the last target box
 * plus the server-reported centroid velocity, and on every display frame the
 * box is dead-reckoned forward by `velocity * age` (clamped) and eased toward
 * that predicted position. That hides the detection interval without letting a
 * stale velocity fling the box past the bird.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** Never lead the true box by more than this many source pixels. */
        const val MAX_EXTRAPOLATION_PX = 24f
        /** Longest stretch of dead reckoning before we assume the bird stopped. */
        const val MAX_EXTRAPOLATION_SEC = 0.08f
        /** Exponential smoothing time constant for the box position. */
        const val SMOOTH_TAU_SEC = 0.030f
        /** A target jump larger than this is a real teleport, not motion. */
        const val SNAP_DISTANCE_PX = 120f
    }

    private class TrackedBox(
        val key: Int,
        var currentRect: RectF,
        var targetRect: RectF,
        var label: String,
        var color: Int,
        var currentAlpha: Float = 0f,
        var targetAlpha: Float = 1f,
        var isAlive: Boolean = true,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var targetChangedAtMs: Long = 0L
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

        val now = SystemClock.uptimeMillis()
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
            val hasGeminiAlert = (item.geminiVerified == true || !item.geminiSummary.isNullOrBlank()) &&
                (tier.equals("High Concern", ignoreCase = true) || tier.equals("Suspicious", ignoreCase = true))

            val label = buildString {
                if (trackId > 0) append('#').append(trackId) else append("CHICKEN")
                when {
                    hasGeminiAlert && tier.equals("High Concern", ignoreCase = true) -> append(" CRITICAL ALERT")
                    tier.equals("High Concern", ignoreCase = true) -> append(" EVALUATING...")
                    item.behavior?.equals("recumbent", ignoreCase = true) == true -> append(" RECUMBENT")
                    tier.equals("Suspicious", ignoreCase = true) -> append(" ATTENTION")
                    tier.equals("Monitor", ignoreCase = true) -> append(" WATCH")
                    else -> item.behavior?.takeIf { it.isNotBlank() }?.let {
                        append(' ').append(it.uppercase(java.util.Locale.getDefault()))
                    }
                }
                if (item.geminiVerified == true) append(" \u2713")
            }
            val color = colorForDetection(tier, hasGeminiAlert)
            val newTarget = RectF(left, top, right, bottom)
            val velocityX = item.velocity?.getOrNull(0)?.toFloat() ?: 0f
            val velocityY = item.velocity?.getOrNull(1)?.toFloat() ?: 0f

            val existing = activeTracks[key]
            if (existing != null) {
                val wasAlive = existing.isAlive
                val jumped = abs(existing.targetRect.left - newTarget.left) > SNAP_DISTANCE_PX ||
                    abs(existing.targetRect.top - newTarget.top) > SNAP_DISTANCE_PX ||
                    abs(existing.targetRect.right - newTarget.right) > SNAP_DISTANCE_PX ||
                    abs(existing.targetRect.bottom - newTarget.bottom) > SNAP_DISTANCE_PX
                val targetMoved = jumped ||
                    abs(existing.targetRect.left - newTarget.left) > 0.5f ||
                    abs(existing.targetRect.top - newTarget.top) > 0.5f ||
                    abs(existing.targetRect.right - newTarget.right) > 0.5f ||
                    abs(existing.targetRect.bottom - newTarget.bottom) > 0.5f
                if (targetMoved || !wasAlive) {
                    existing.targetRect.set(newTarget)
                    existing.targetChangedAtMs = now
                }
                // Reappearing after occlusion, or a genuine teleport: snap instead
                // of sliding the box across the screen.
                if (!wasAlive || jumped) {
                    existing.currentRect.set(newTarget)
                }
                existing.velocityX = velocityX
                existing.velocityY = velocityY
                existing.label = label
                existing.color = color
                existing.targetAlpha = 1f
                existing.currentAlpha = 1f
                existing.isAlive = true
            } else {
                // New detection appears instantly with full opacity (no fade delay)
                activeTracks[key] = TrackedBox(
                    key = key,
                    currentRect = RectF(newTarget),
                    targetRect = RectF(newTarget),
                    label = label,
                    color = color,
                    currentAlpha = 1f,
                    targetAlpha = 1f,
                    isAlive = true,
                    velocityX = velocityX,
                    velocityY = velocityY,
                    targetChangedAtMs = now
                )
            }
        }

        // Mark missing tracks to fade out quickly
        activeTracks.values.forEach { track ->
            if (track.key !in seenKeys) {
                track.targetAlpha = 0f
                track.isAlive = false
            }
        }

        // Do not touch lastFrameTimeMs here: onDraw owns it so the smoothing
        // timestep stays continuous across payload updates.
        postInvalidateOnAnimation()
    }

    fun clear() {
        if (activeTracks.isNotEmpty()) {
            activeTracks.clear()
            invalidate()
        }
    }

    private fun colorForDetection(tier: String, hasGeminiAlert: Boolean): Int = when {
        // Red ONLY when confirmed as an anomaly/alert by Gemini!
        hasGeminiAlert && tier.equals("High Concern", ignoreCase = true) -> Color.parseColor("#FF4646")
        // If locally high concern or suspicious, show Orange (under observation) prior to Gemini verdict
        tier.equals("High Concern", ignoreCase = true) || tier.equals("Suspicious", ignoreCase = true) -> Color.parseColor("#FF8C00")
        tier.equals("Monitor", ignoreCase = true) -> Color.parseColor("#00D7FF")
        else -> Color.parseColor("#00E676")
    }

    private fun lerp(current: Float, target: Float, factor: Float): Float {
        return current + (target - current) * factor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeTracks.isEmpty() || width == 0 || height == 0) return

        val now = SystemClock.uptimeMillis()
        val dtSec = ((now - lastFrameTimeMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameTimeMs = now
        // First-order ease toward the predicted box; ~0.43 per 60 Hz frame.
        val smooth = 1f - exp(-dtSec / SMOOTH_TAU_SEC)

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

            // Glide the box between detector updates: dead-reckon forward using
            // the server-reported velocity (clamped) and ease toward that point.
            if (track.isAlive) {
                val ageSec = ((now - track.targetChangedAtMs) / 1000f)
                    .coerceIn(0f, MAX_EXTRAPOLATION_SEC)
                var leadX = track.velocityX * ageSec
                var leadY = track.velocityY * ageSec
                val leadMag = hypot(leadX, leadY)
                if (leadMag > MAX_EXTRAPOLATION_PX) {
                    val k = MAX_EXTRAPOLATION_PX / leadMag
                    leadX *= k
                    leadY *= k
                }
                track.currentRect.set(
                    lerp(track.currentRect.left, track.targetRect.left + leadX, smooth),
                    lerp(track.currentRect.top, track.targetRect.top + leadY, smooth),
                    lerp(track.currentRect.right, track.targetRect.right + leadX, smooth),
                    lerp(track.currentRect.bottom, track.targetRect.bottom + leadY, smooth)
                )
                track.currentAlpha = 1f
                // Keep redrawing at display rate while any box is live.
                needsAnotherFrame = true
            } else {
                // Smooth fast fade-out for occluded/departed tracks
                track.currentAlpha = lerp(track.currentAlpha, 0f, 0.40f)
                if (track.currentAlpha > 0.03f) {
                    needsAnotherFrame = true
                }
            }

            // Prune dead tracks once fully faded out
            if (!track.isAlive && track.currentAlpha < 0.03f) {
                iterator.remove()
                continue
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

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
        var lastSeenMs: Long = SystemClock.uptimeMillis()
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
        data class PendingItem(val key: Int, val rect: RectF, val item: DetectionItem)
        val incoming = mutableListOf<PendingItem>()

        items.orEmpty().forEachIndexed { index, item ->
            val bbox = item.bbox
            if (bbox == null || bbox.size < 4) return@forEachIndexed
            val left = bbox[0].toFloat()
            val top = bbox[1].toFloat()
            val right = bbox[2].toFloat()
            val bottom = bbox[3].toFloat()
            if (right <= left || bottom <= top) return@forEachIndexed

            val trackId = item.trackId ?: 0
            val key = if (trackId > 0) trackId else -(index + 1000)
            seenKeys.add(key)
            incoming.add(PendingItem(key, RectF(left, top, right, bottom), item))
        }

        incoming.forEach { pending ->
            val key = pending.key
            val newTarget = pending.rect
            val item = pending.item
            val trackId = item.trackId ?: 0

            val tier = item.anomalyTier ?: "Normal"
            val isGemini = item.geminiVerified == true
            val isHighConcern = tier.equals("High Concern", ignoreCase = true)
            val isSuspicious = tier.equals("Suspicious", ignoreCase = true)
            val isMonitor = tier.equals("Monitor", ignoreCase = true)
            val isRecumbent = item.behavior?.equals("recumbent", ignoreCase = true) == true
            val behaviorLower = item.behavior?.lowercase(java.util.Locale.getDefault()).orEmpty()
            val isWalking = behaviorLower == "walk" || ((item.motion ?: 0.0) >= 2.0 && (item.stationarySec ?: 0.0) < 3.0)
            val isPecking = behaviorLower == "peck"
            val isResting = behaviorLower in listOf("rest", "resting", "sleep", "sleeping")
            val isStanding = behaviorLower == "stand" || (!isWalking && !isPecking && !isResting && !isRecumbent)
            val isActivelyMoving = isWalking || isPecking || (item.motion ?: 0.0) >= 1.5

            val label = buildString {
                if (trackId > 0) append('#').append(trackId) else append("CHICKEN")
                when {
                    // Authoritative Gemini clinical diagnosis
                    isGemini && isHighConcern -> append(" CRITICAL ALERT \u2713")
                    isGemini && isSuspicious -> append(" ATTENTION \u2713")
                    isGemini && isMonitor -> append(" WATCH \u2713")
                    isGemini -> {
                        val beh = when {
                            isWalking -> "WALK"
                            isPecking -> "PECK"
                            isResting -> "REST"
                            isRecumbent -> "RECUMBENT"
                            else -> "STAND"
                        }
                        append(' ').append(beh).append(" \u2713")
                    }
                    // Local Heuristics
                    // 1. High Concern -> Evaluating (prior to Gemini)
                    isHighConcern -> append(" EVALUATING...")
                    // 2. Recumbent -> Recumbent
                    isRecumbent -> append(" RECUMBENT")
                    // 3. Resting / Sleep (eyes closed, calm on litter, non-locomoting)
                    isResting && !isActivelyMoving -> append(" REST")
                    // 4. Actively walking / locomotion
                    isWalking -> append(" WALK")
                    // 5. Feeding / Pecking ground
                    isPecking -> append(" PECK")
                    // 6. Extended stationary observation -> WATCH (never Attention until Gemini verifies)
                    isSuspicious || isMonitor -> append(" WATCH")
                    // 7. Normal standing / looking around
                    else -> append(" STAND")
                }
            }
            val color = colorForDetection(tier, isGemini, isRecumbent, isActivelyMoving || isStanding || isResting)

            val existing = activeTracks[key]
            if (existing != null) {
                existing.targetRect.set(newTarget)
                existing.label = label
                existing.color = color
                existing.targetAlpha = 1f
                existing.isAlive = true
                existing.lastSeenMs = now
            } else {
                // Running chicken handoff check: if a new box appears near an un-seen old box,
                // immediately hand off position and purge the old box so no stuck duplicate appears!
                val newCenterX = newTarget.centerX()
                val newCenterY = newTarget.centerY()
                var handedOffRect: RectF? = null

                val unseenIter = activeTracks.entries.iterator()
                while (unseenIter.hasNext()) {
                    val entry = unseenIter.next()
                    if (entry.key !in seenKeys) {
                        val oldTrack = entry.value
                        val oldCenterX = oldTrack.currentRect.centerX()
                        val oldCenterY = oldTrack.currentRect.centerY()
                        val dist = Math.hypot(
                            (newCenterX - oldCenterX).toDouble(),
                            (newCenterY - oldCenterY).toDouble()
                        )
                        // If within 250px, this is the same chicken running fast
                        if (dist < 250.0) {
                            handedOffRect = RectF(oldTrack.currentRect)
                            unseenIter.remove() // Instantly delete the old stuck box!
                            break
                        }
                    }
                }

                val initialRect = handedOffRect ?: RectF(newTarget)
                activeTracks[key] = TrackedBox(
                    key = key,
                    currentRect = initialRect,
                    targetRect = RectF(newTarget),
                    label = label,
                    color = color,
                    currentAlpha = 1f,
                    targetAlpha = 1f,
                    isAlive = true,
                    lastSeenMs = now
                )
            }
        }

        // Departed tracks: immediately start smooth fast fade-out so no box is left stuck on screen
        activeTracks.values.forEach { track ->
            if (track.key !in seenKeys) {
                track.targetAlpha = 0f
                track.isAlive = false
            }
        }

        lastFrameTimeMs = now
        postInvalidateOnAnimation()
    }

    fun clear() {
        if (activeTracks.isNotEmpty()) {
            activeTracks.clear()
            invalidate()
        }
    }

    private fun colorForDetection(
        tier: String,
        isGeminiVerified: Boolean,
        isRecumbent: Boolean,
        isHealthyActivity: Boolean
    ): Int = when {
        // Red ONLY when confirmed as High Concern / Critical by Gemini!
        isGeminiVerified && tier.equals("High Concern", ignoreCase = true) -> Color.parseColor("#FF4646")
        // Orange ONLY when Gemini verified Attention/Suspicious, or locally recumbent/evaluating
        isGeminiVerified && tier.equals("Suspicious", ignoreCase = true) -> Color.parseColor("#FF8C00")
        isRecumbent -> Color.parseColor("#FF8C00")
        tier.equals("High Concern", ignoreCase = true) -> Color.parseColor("#FF8C00")
        // Normal healthy activities (Walking, Standing, Resting, Pecking) are ALWAYS vibrant green!
        isHealthyActivity && (tier.equals("Normal", ignoreCase = true) || !isGeminiVerified && !tier.equals("Suspicious", ignoreCase = true) && !tier.equals("Monitor", ignoreCase = true)) -> Color.parseColor("#00E676")
        // Extended stationary observation: Cyan/Blue for WATCH
        tier.equals("Monitor", ignoreCase = true) || tier.equals("Suspicious", ignoreCase = true) -> Color.parseColor("#00D7FF")
        else -> Color.parseColor("#00E676")
    }

    private fun lerp(current: Float, target: Float, factor: Float): Float {
        return current + (target - current) * factor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeTracks.isEmpty() || width == 0 || height == 0) return

        lastFrameTimeMs = SystemClock.uptimeMillis()

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

            if (track.isAlive) {
                // Silky-smooth 60 FPS motion interpolation (glides continuously without jerky snaps)
                val factor = 0.22f
                track.currentRect.left = lerp(track.currentRect.left, track.targetRect.left, factor)
                track.currentRect.top = lerp(track.currentRect.top, track.targetRect.top, factor)
                track.currentRect.right = lerp(track.currentRect.right, track.targetRect.right, factor)
                track.currentRect.bottom = lerp(track.currentRect.bottom, track.targetRect.bottom, factor)
                track.currentAlpha = 1f

                val diff = Math.abs(track.currentRect.left - track.targetRect.left) +
                           Math.abs(track.currentRect.top - track.targetRect.top) +
                           Math.abs(track.currentRect.right - track.targetRect.right) +
                           Math.abs(track.currentRect.bottom - track.targetRect.bottom)
                if (diff > 0.15f) {
                    needsAnotherFrame = true
                }
            } else {
                // Smooth fast fade-out for departed tracks (clean, non-lingering exit)
                track.currentAlpha = lerp(track.currentAlpha, 0f, 0.25f)
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

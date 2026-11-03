package com.example.poultrysense.ui.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.poultrysense.data.models.DetectionItem

/**
 * Draws AI bounding boxes on top of the smooth raw camera preview.
 *
 * The server publishes detections at 2-3 Hz while the preview runs at camera
 * frame rate. Mapping uses the same centre-crop transform as the ImageView, so
 * boxes stay aligned at any viewport size.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Box(
        val rect: RectF,
        val label: String,
        val color: Int
    )

    private var boxes: List<Box> = emptyList()
    private var sourceWidth = 640
    private var sourceHeight = 480

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

        boxes = items.orEmpty().mapNotNull { item ->
            val bbox = item.bbox
            if (bbox == null || bbox.size < 4) return@mapNotNull null
            val left = bbox[0].toFloat()
            val top = bbox[1].toFloat()
            val right = bbox[2].toFloat()
            val bottom = bbox[3].toFloat()
            if (right <= left || bottom <= top) return@mapNotNull null

            val trackId = item.trackId ?: 0
            val tier = item.anomalyTier ?: "Normal"
            val label = buildString {
                append('#').append(trackId)
                when {
                    tier == "High Concern" -> append(" ALERT")
                    tier == "Suspicious" -> append(" ATTENTION")
                    tier == "Monitor" -> append(" WATCH")
                    else -> item.behavior?.takeIf { it.isNotBlank() }?.let {
                        append(' ').append(it.uppercase())
                    }
                }
            }

            Box(
                rect = RectF(left, top, right, bottom),
                label = label,
                color = colorForTier(tier)
            )
        }
        invalidate()
    }

    fun clear() {
        if (boxes.isNotEmpty()) {
            boxes = emptyList()
            invalidate()
        }
    }

    private fun colorForTier(tier: String): Int = when (tier) {
        "High Concern" -> Color.parseColor("#FF4646")
        "Suspicious" -> Color.parseColor("#FF8C00")
        "Monitor" -> Color.parseColor("#00D7FF")
        else -> Color.parseColor("#00E676")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty() || width == 0 || height == 0) return

        // Match ImageView scaleType="centerCrop".
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        val mapped = RectF()
        val density = resources.displayMetrics.density
        boxes.forEach { box ->
            mapped.set(
                box.rect.left * scale + offsetX,
                box.rect.top * scale + offsetY,
                box.rect.right * scale + offsetX,
                box.rect.bottom * scale + offsetY
            )

            boxPaint.color = box.color
            canvas.drawRoundRect(mapped, 6f * density, 6f * density, boxPaint)

            val textWidth = textPaint.measureText(box.label)
            val padding = 6f * density
            val labelHeight = textPaint.textSize + padding
            val labelLeft = mapped.left.coerceAtLeast(0f)
            val labelTop = (mapped.top - labelHeight).coerceAtLeast(0f)
            val labelRight = (labelLeft + textWidth + padding * 2)
                .coerceAtMost(width.toFloat())
            val labelBottom = (labelTop + labelHeight).coerceAtMost(height.toFloat())

            canvas.drawRoundRect(
                RectF(labelLeft, labelTop, labelRight, labelBottom),
                6f * density, 6f * density, labelBackgroundPaint
            )
            canvas.drawText(
                box.label,
                labelLeft + padding,
                labelBottom - padding,
                textPaint
            )
        }
    }
}

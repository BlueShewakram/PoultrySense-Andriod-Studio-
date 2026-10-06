package com.example.poultrysense.ui.monitor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.poultrysense.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Premium 2D Top-Down Poultry Farm Simulation View.
 *
 * Features:
 * - High-Res Farm Artwork with Center-Crop Fill (Zero white borders).
 * - Proud Poultry Farm Owner walking along the main road with egg basket.
 * - Walking farm caretakers & vet inspector doing their rounds.
 * - Fluffy animated baby chicks scurrying & pecking in the Brooder.
 * - Free-range hens pecking and dust-bathing in their pastures.
 * - Soft organic ground drop shadows (no gray boxes).
 * - Smooth camera zoom & tap selection into the 3 interactive coops.
 */
class PoultryFarmMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PoultryZoneId {
        OVERVIEW,
        COOP_1,
        BROODER,
        NESTING
    }

    data class ZoneStats(
        val chickenCount: Int = 48,
        val healthIndex: Int = 98,
        val temperature: Double = 26.5
    )

    var onZoneSelectedListener: ((PoultryZoneId) -> Unit)? = null

    // ─── Camera & Viewport Transformations ───
    private var currentScale = 1.0f
    private var currentTransX = 0.0f
    private var currentTransY = 0.0f

    private var targetScale = 1.0f
    private var targetTransX = 0.0f
    private var targetTransY = 0.0f

    private var cameraAnimator: ValueAnimator? = null
    var selectedZone: PoultryZoneId = PoultryZoneId.OVERVIEW
        private set

    // Live Stats Cache per Zone
    private val zoneStatsMap = mutableMapOf(
        PoultryZoneId.COOP_1 to ZoneStats(48, 98, 26.4),
        PoultryZoneId.BROODER to ZoneStats(25, 99, 32.2),
        PoultryZoneId.NESTING to ZoneStats(55, 96, 25.8)
    )

    // Virtual Farm Canvas dimensions (world coords: 1200 x 900)
    private val worldWidth = 1200f
    private val worldHeight = 900f

    // Bounds of the 3 Interactive Poultry Coops in 1200x900 coordinate space
    private val rectCoop1 = RectF(60f, 50f, 550f, 410f)
    private val rectBrooder = RectF(650f, 50f, 1140f, 410f)
    private val rectNesting = RectF(60f, 480f, 550f, 850f)

    // Silos area (background aesthetic, non-clickable)
    private val rectSilos = RectF(650f, 480f, 1140f, 850f)

    // ─── High-Res Image Assets ───
    private var farmMapBitmap: Bitmap? = null
    private var ownerBitmap: Bitmap? = null
    private var workerBitmap: Bitmap? = null
    private var inspectorBitmap: Bitmap? = null
    private var chickenBitmap: Bitmap? = null
    private var chickBitmap: Bitmap? = null

    // ─── Graphics Paints ───
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Natural Ground Drop Shadow (Translucent warm green-tinted shadow under feet)
    private val groundShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#441B320B")
        style = Paint.Style.FILL
    }
    private val cloudShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
        style = Paint.Style.FILL
    }

    // Selection Halos
    private val selectionHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#550E5924")
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E5924")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Modern Translucent Badge Paints
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5FFFFFF")
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 15f
        isFakeBoldText = true
        style = Paint.Style.FILL
    }
    private val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    private val bgClearPaint = Paint().apply {
        color = Color.parseColor("#5A9031") // Farm grass perimeter color
    }

    // ─── Character Roles & Living Entities State ───
    enum class PersonRole {
        OWNER,      // Proud Poultry Owner walking with fresh egg basket
        FEEDER,     // Caretaker carrying feed bucket
        INSPECTOR   // Vet Inspector with clipboard
    }

    private class Person(
        val role: PersonRole,
        val waypoints: List<Pair<Float, Float>>,
        var currentWpIndex: Int = 0,
        var x: Float = 0f,
        var y: Float = 0f,
        var speed: Float = 1.2f,
        var legCycle: Float = 0f,
        var facingRight: Boolean = true,
        var pauseTimer: Long = 0
    )

    // Animated People on the Farm
    private val people = listOf(
        // 1. POULTRY OWNER: Walks proudly along the main horizontal road (Y = 450) with egg basket
        Person(
            role = PersonRole.OWNER,
            waypoints = listOf(
                Pair(120f, 450f),
                Pair(1080f, 450f),
                Pair(120f, 450f)
            ),
            x = 220f,
            y = 450f,
            speed = 1.35f,
            facingRight = true
        ),
        // 2. Feeder Caretaker: Silos to Coop 1 gate and back
        Person(
            role = PersonRole.FEEDER,
            waypoints = listOf(
                Pair(600f, 650f),
                Pair(600f, 450f),
                Pair(470f, 450f), // Pauses at Coop 1 entrance
                Pair(600f, 450f),
                Pair(600f, 650f)
            ),
            x = 600f,
            y = 560f,
            speed = 1.2f,
            facingRight = false
        ),
        // 3. Vet Inspector: North road inspecting Brooder & Coop
        Person(
            role = PersonRole.INSPECTOR,
            waypoints = listOf(
                Pair(600f, 450f),
                Pair(600f, 200f),
                Pair(720f, 200f), // Pauses near Brooder nursery
                Pair(600f, 200f),
                Pair(600f, 450f)
            ),
            x = 600f,
            y = 300f,
            speed = 1.05f,
            facingRight = true
        ),
        // 4. Assistant Caretaker: Rounds near southwest road by Nesting pasture
        Person(
            role = PersonRole.FEEDER,
            waypoints = listOf(
                Pair(450f, 450f),
                Pair(320f, 450f),
                Pair(320f, 680f),
                Pair(450f, 680f),
                Pair(450f, 450f)
            ),
            x = 320f,
            y = 450f,
            speed = 1.15f,
            facingRight = false
        )
    )

    private class Chicken(
        val penBounds: RectF,
        var x: Float,
        var y: Float,
        val isChick: Boolean = false,
        var targetX: Float = 0f,
        var targetY: Float = 0f,
        var isPecking: Boolean = false,
        var peckTimer: Long = 0,
        var peckCycle: Float = 0f,
        var facingRight: Boolean = true,
        var hopOffset: Float = 0f
    )

    private val chickens = mutableListOf<Chicken>()
    private var cloudX = 0f
    private var lastFrameTime = SystemClock.uptimeMillis()

    init {
        loadBitmaps()

        // Coop 1: 5 adult hens placed strictly on the green yard
        val coopYard = RectF(160f, 220f, 490f, 380f)
        for (i in 0 until 5) {
            val cx = coopYard.left + 40f + (i % 3) * 110f + (Math.random() * 25).toFloat()
            val cy = coopYard.top + 30f + (i / 3) * 60f + (Math.random() * 25).toFloat()
            chickens.add(Chicken(coopYard, cx, cy, isChick = false))
        }

        // Brooder: 6 active animated baby chicks scurrying and pecking!
        val brooderYard = RectF(740f, 220f, 1060f, 380f)
        for (i in 0 until 6) {
            val cx = brooderYard.left + 30f + (i % 3) * 100f + (Math.random() * 20).toFloat()
            val cy = brooderYard.top + 30f + (i / 3) * 60f + (Math.random() * 20).toFloat()
            chickens.add(Chicken(brooderYard, cx, cy, isChick = true))
        }

        // Nesting: 6 free-range layers grazing in the pasture
        val nestingYard = RectF(140f, 540f, 490f, 800f)
        for (i in 0 until 6) {
            val cx = nestingYard.left + 50f + (i % 3) * 120f + (Math.random() * 30).toFloat()
            val cy = nestingYard.top + 40f + (i / 3) * 90f + (Math.random() * 30).toFloat()
            chickens.add(Chicken(nestingYard, cx, cy, isChick = false))
        }
    }

    private fun loadBitmaps() {
        try {
            farmMapBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_poultry_farm_map)
        } catch (_: Exception) {}
        try {
            ownerBitmap = BitmapFactory.decodeResource(resources, R.drawable.sprite_poultry_owner)
        } catch (_: Exception) {}
        try {
            workerBitmap = BitmapFactory.decodeResource(resources, R.drawable.sprite_farmer_worker)
        } catch (_: Exception) {}
        try {
            inspectorBitmap = BitmapFactory.decodeResource(resources, R.drawable.sprite_farmer_inspector)
        } catch (_: Exception) {}
        try {
            chickenBitmap = BitmapFactory.decodeResource(resources, R.drawable.sprite_chicken_hen)
        } catch (_: Exception) {}
        try {
            chickBitmap = BitmapFactory.decodeResource(resources, R.drawable.sprite_baby_chick)
        } catch (_: Exception) {}
    }

    /**
     * Updates live telemetry metrics for a zone.
     */
    fun updateZoneData(zoneId: PoultryZoneId, count: Int, health: Int, temp: Double) {
        zoneStatsMap[zoneId] = ZoneStats(count, health, temp)
        postInvalidateOnAnimation()
    }

    /**
     * Programmatically selects and smoothly zooms into a zone.
     */
    fun selectZone(zoneId: PoultryZoneId, triggerListener: Boolean = true) {
        if (selectedZone == zoneId) return
        selectedZone = zoneId

        when (zoneId) {
            PoultryZoneId.OVERVIEW -> {
                targetScale = 1.0f
                targetTransX = 0f
                targetTransY = 0f
            }
            PoultryZoneId.COOP_1 -> {
                targetScale = 1.85f
                targetTransX = (worldWidth / 2f) - rectCoop1.centerX()
                targetTransY = (worldHeight / 2f) - rectCoop1.centerY()
            }
            PoultryZoneId.BROODER -> {
                targetScale = 1.85f
                targetTransX = (worldWidth / 2f) - rectBrooder.centerX()
                targetTransY = (worldHeight / 2f) - rectBrooder.centerY()
            }
            PoultryZoneId.NESTING -> {
                targetScale = 1.85f
                targetTransX = (worldWidth / 2f) - rectNesting.centerX()
                targetTransY = (worldHeight / 2f) - rectNesting.centerY()
            }
        }

        animateCamera()

        if (triggerListener) {
            onZoneSelectedListener?.invoke(zoneId)
        }
    }

    private fun animateCamera() {
        cameraAnimator?.cancel()
        val startScale = currentScale
        val startX = currentTransX
        val startY = currentTransY

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { animator ->
                val f = animator.animatedFraction
                currentScale = startScale + (targetScale - startScale) * f
                currentTransX = startX + (targetTransX - startX) * f
                currentTransY = startY + (targetTransY - startY) * f
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            val worldPt = screenToWorld(event.x, event.y)

            // ONLY Poultry Monitoring coops are clickable!
            when {
                rectCoop1.contains(worldPt.first, worldPt.second) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectZone(PoultryZoneId.COOP_1)
                    return true
                }
                rectBrooder.contains(worldPt.first, worldPt.second) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectZone(PoultryZoneId.BROODER)
                    return true
                }
                rectNesting.contains(worldPt.first, worldPt.second) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectZone(PoultryZoneId.NESTING)
                    return true
                }
                else -> {
                    // Tapping outside on the pathways or background resets to Overview
                    if (selectedZone != PoultryZoneId.OVERVIEW) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        selectZone(PoultryZoneId.OVERVIEW)
                        return true
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Converts screen touch coordinates to world coordinates.
     * Uses Center-Crop fillScale (coerceAtLeast) matching onDraw.
     */
    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)

        val fillScale = (viewW / worldWidth).coerceAtLeast(viewH / worldHeight)
        val baseTransX = (viewW - worldWidth * fillScale) / 2f
        val baseTransY = (viewH - worldHeight * fillScale) / 2f

        val centerX = viewW / 2f
        val centerY = viewH / 2f

        val sx = (screenX - centerX) / currentScale + centerX
        val sy = (screenY - centerY) / currentScale + centerY

        val wx = (sx - baseTransX - currentTransX * fillScale) / fillScale
        val wy = (sy - baseTransY - currentTransY * fillScale) / fillScale
        return Pair(wx, wy)
    }

    // ─── 60 FPS Engine Update ───
    private fun updateEntities(deltaTime: Float) {
        val now = SystemClock.uptimeMillis()

        // 1. Update Cloud Drift
        cloudX += 0.35f * (deltaTime / 16.6f)
        if (cloudX > worldWidth + 300f) cloudX = -300f

        // 2. Update People along roads
        for (p in people) {
            if (now < p.pauseTimer) {
                // Paused at inspection or feeding spot
                continue
            }

            val target = p.waypoints[p.currentWpIndex]
            val dx = target.first - p.x
            val dy = target.second - p.y
            val dist = hypot(dx, dy)

            if (dist < 6f) {
                // Arrived at waypoint: pause if at special location
                if (target.first == 470f && target.second == 450f) {
                    p.pauseTimer = now + 2400L // 2.4s pause at Coop 1
                } else if (target.first == 720f && target.second == 200f) {
                    p.pauseTimer = now + 2000L // 2.0s pause at Brooder
                }
                p.currentWpIndex = (p.currentWpIndex + 1) % p.waypoints.size
            } else {
                val step = p.speed * (deltaTime / 16.6f) * 1.4f
                p.x += (dx / dist) * step
                p.y += (dy / dist) * step
                p.legCycle += 0.22f
                if (dx > 2f) p.facingRight = true
                else if (dx < -2f) p.facingRight = false
            }
        }

        // 3. Update Chickens & Baby Chicks
        for (c in chickens) {
            if (c.isPecking) {
                c.peckCycle += if (c.isChick) 0.42f else 0.28f
                if (now > c.peckTimer) {
                    c.isPecking = false
                    val inset = if (c.isChick) 20f else 30f
                    c.targetX = c.penBounds.left + inset + (Math.random() * (c.penBounds.width() - inset * 2)).toFloat()
                    c.targetY = c.penBounds.top + inset + (Math.random() * (c.penBounds.height() - inset * 2)).toFloat()
                }
            } else {
                val dx = c.targetX - c.x
                val dy = c.targetY - c.y
                val dist = hypot(dx, dy)
                if (dist < 4f || c.targetX == 0f) {
                    c.isPecking = true
                    val duration = if (c.isChick) (1000 + Math.random() * 1800).toLong() else (1800 + Math.random() * 2600).toLong()
                    c.peckTimer = now + duration
                } else {
                    val spd = (if (c.isChick) 0.85f else 0.55f) * (deltaTime / 16.6f)
                    c.x += (dx / dist) * spd
                    c.y += (dy / dist) * spd
                    val hopFreq = if (c.isChick) 80.0 else 110.0
                    val hopHeight = if (c.isChick) 2.0f else 2.5f
                    c.hopOffset = abs(sin(now / hopFreq)).toFloat() * hopHeight
                    if (dx > 1f) c.facingRight = true
                    else if (dx < -1f) c.facingRight = false
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        val delta = (now - lastFrameTime).coerceIn(10L, 50L).toFloat()
        lastFrameTime = now
        updateEntities(delta)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Center-Crop fill scale (Zero white borders!)
        val fillScale = (viewW / worldWidth).coerceAtLeast(viewH / worldHeight)
        val baseTransX = (viewW - worldWidth * fillScale) / 2f
        val baseTransY = (viewH - worldHeight * fillScale) / 2f

        // Clear with pasture green
        canvas.drawRect(0f, 0f, viewW, viewH, bgClearPaint)

        canvas.save()

        // Apply Center-Crop base transform
        canvas.translate(baseTransX, baseTransY)
        canvas.scale(fillScale, fillScale)

        // Apply interactive camera zoom & pan
        canvas.translate(worldWidth / 2f, worldHeight / 2f)
        canvas.scale(currentScale, currentScale)
        canvas.translate(-worldWidth / 2f + currentTransX, -worldHeight / 2f + currentTransY)

        // ─── 1. Farm Environment Artwork ───
        val mapBmp = farmMapBitmap
        if (mapBmp != null && !mapBmp.isRecycled) {
            canvas.drawBitmap(mapBmp, null, RectF(0f, 0f, worldWidth, worldHeight), bitmapPaint)
        }

        // ─── 2. Drifting Soft Cloud Shadows ───
        drawCloudShadows(canvas)

        // ─── 3. Interactive Selection Halo around Active Coop ───
        when (selectedZone) {
            PoultryZoneId.COOP_1 -> drawSelectionFrame(canvas, rectCoop1)
            PoultryZoneId.BROODER -> drawSelectionFrame(canvas, rectBrooder)
            PoultryZoneId.NESTING -> drawSelectionFrame(canvas, rectNesting)
            PoultryZoneId.OVERVIEW -> { /* No halo */ }
        }

        // ─── 4. Animated People (Poultry Owner, Feeder, Inspector) with Soft Ground Shadows ───
        for (p in people) {
            drawPerson(canvas, p)
        }

        // ─── 5. Roaming Hens & Scurrying Baby Chicks with Ground Shadows ───
        for (c in chickens) {
            drawChicken(canvas, c)
        }

        // ─── 6. Modern Translucent Badges over Coops ───
        drawZoneBadge(canvas, rectCoop1, "Coop 1 • CAM 1", zoneStatsMap[PoultryZoneId.COOP_1]?.chickenCount ?: 48)
        drawZoneBadge(canvas, rectBrooder, "Brooder • CAM 2", zoneStatsMap[PoultryZoneId.BROODER]?.chickenCount ?: 25)
        drawZoneBadge(canvas, rectNesting, "Nesting • CAM 3", zoneStatsMap[PoultryZoneId.NESTING]?.chickenCount ?: 55)

        canvas.restore()

        // Trigger smooth 60 FPS animation loop
        postInvalidateOnAnimation()
    }

    private fun drawCloudShadows(canvas: Canvas) {
        val cx = cloudX
        canvas.drawOval(RectF(cx, 120f, cx + 280f, 260f), cloudShadowPaint)
        canvas.drawOval(RectF(cx - 380f, 520f, cx - 120f, 660f), cloudShadowPaint)
    }

    private fun drawSelectionFrame(canvas: Canvas, rect: RectF) {
        val pulse = (sin(SystemClock.uptimeMillis() / 300.0) * 3f).toFloat()
        val haloRect = RectF(rect.left - pulse, rect.top - pulse, rect.right + pulse, rect.bottom + pulse)
        canvas.drawRoundRect(haloRect, 24f, 24f, selectionHaloPaint)
        canvas.drawRoundRect(rect, 24f, 24f, selectionStrokePaint)
    }

    private fun drawPerson(canvas: Canvas, p: Person) {
        val bmp = when (p.role) {
            PersonRole.OWNER -> ownerBitmap
            PersonRole.INSPECTOR -> inspectorBitmap ?: workerBitmap
            PersonRole.FEEDER -> workerBitmap
        }

        val bob = (sin(p.legCycle.toDouble()) * 3.0f).toFloat()
        val sway = (sin(p.legCycle.toDouble()) * 3.5f).toFloat()

        if (bmp != null && !bmp.isRecycled) {
            // Soft Oval Ground Shadow under feet (No gray box!)
            val shadowW = if (p.role == PersonRole.OWNER) 34f + (bob * 0.8f) else 30f + (bob * 0.8f)
            val shadowH = 14f
            val shadowRect = RectF(p.x - shadowW / 2f, p.y + 24f, p.x + shadowW / 2f, p.y + 24f + shadowH)
            canvas.drawOval(shadowRect, groundShadowPaint)

            // Character Sprite
            canvas.save()
            canvas.translate(p.x, p.y + bob)
            canvas.rotate(sway)

            val drawW = if (p.role == PersonRole.OWNER) 54f else if (p.role == PersonRole.INSPECTOR) 46f else 50f
            val drawH = if (p.role == PersonRole.OWNER) 54f else if (p.role == PersonRole.INSPECTOR) 52f else 50f
            val matrix = Matrix()
            if (!p.facingRight) {
                matrix.postScale(-1f, 1f, drawW / 2f, drawH / 2f)
            }
            matrix.postTranslate(-drawW / 2f, -drawH / 2f)
            matrix.postScale(drawW / bmp.width, drawH / bmp.height)

            canvas.concat(matrix)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()
        }
    }

    private fun drawChicken(canvas: Canvas, c: Chicken) {
        val bmp = if (c.isChick) chickBitmap ?: chickenBitmap else chickenBitmap

        // Rotational pecking angle
        val maxPeck = if (c.isChick) 16f else 20f
        val peckAngle = if (c.isPecking) (sin(c.peckCycle.toDouble()) * maxPeck).toFloat().coerceAtLeast(0f) else 0f
        val peckDown = if (c.isPecking) (sin(c.peckCycle.toDouble()) * 3.0f).toFloat().coerceAtLeast(0f) else 0f

        if (bmp != null && !bmp.isRecycled) {
            if (c.isChick) {
                // Baby Chick: Small cute ground shadow
                val shadowW = 18f
                val shadowH = 8f
                val shadowRect = RectF(c.x - shadowW / 2f, c.y + 11f - c.hopOffset, c.x + shadowW / 2f, c.y + 11f + shadowH - c.hopOffset)
                canvas.drawOval(shadowRect, groundShadowPaint)

                // Baby Chick Sprite
                canvas.save()
                canvas.translate(c.x, c.y + peckDown - c.hopOffset)
                val rot = if (c.facingRight) peckAngle else -peckAngle
                canvas.rotate(rot, 0f, 8f)

                val drawW = 24f
                val drawH = 26f
                val matrix = Matrix()
                if (!c.facingRight) {
                    matrix.postScale(-1f, 1f, drawW / 2f, drawH / 2f)
                }
                matrix.postTranslate(-drawW / 2f, -drawH / 2f)
                matrix.postScale(drawW / bmp.width, drawH / bmp.height)

                canvas.concat(matrix)
                canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
                canvas.restore()
            } else {
                // Adult Hen: Ground shadow
                val shadowW = 26f - peckDown * 0.5f
                val shadowH = 10f
                val shadowRect = RectF(c.x - shadowW / 2f, c.y + 12f - c.hopOffset, c.x + shadowW / 2f, c.y + 12f + shadowH - c.hopOffset)
                canvas.drawOval(shadowRect, groundShadowPaint)

                // Adult Hen Sprite
                canvas.save()
                canvas.translate(c.x, c.y + peckDown - c.hopOffset)
                val rot = if (c.facingRight) peckAngle else -peckAngle
                canvas.rotate(rot, 0f, 10f)

                val drawSize = 36f
                val matrix = Matrix()
                if (!c.facingRight) {
                    matrix.postScale(-1f, 1f, drawSize / 2f, drawSize / 2f)
                }
                matrix.postTranslate(-drawSize / 2f, -drawSize / 2f)
                matrix.postScale(drawSize / bmp.width, drawSize / bmp.height)

                canvas.concat(matrix)
                canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
                canvas.restore()
            }
        }
    }

    private fun drawZoneBadge(canvas: Canvas, rect: RectF, label: String, count: Int) {
        val badgeW = 150f
        val badgeH = 26f
        val bx = rect.centerX() - badgeW / 2f
        val by = rect.top + 8f

        val bgRect = RectF(bx, by, bx + badgeW, by + badgeH)
        canvas.drawRoundRect(bgRect, 13f, 13f, badgeBgPaint)
        canvas.drawRoundRect(bgRect, 13f, 13f, badgeStrokePaint)

        // Live Green Dot (pulsing)
        val pulse = (sin(SystemClock.uptimeMillis() / 250.0) * 0.8f).toFloat()
        canvas.drawCircle(bx + 14f, by + badgeH / 2f, 4f + pulse, liveDotPaint)

        // Text
        val fullText = "$label • $count"
        canvas.drawText(fullText, bx + 24f, by + 18f, badgeTextPaint)
    }
}

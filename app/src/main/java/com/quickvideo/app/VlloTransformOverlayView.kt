package com.quickvideo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * VLLO-style Interactive Preview Overlay:
 * - Two-finger pinch-to-zoom & twist-to-rotate anywhere on the preview
 * - One-finger corner handle drag (or circular drag in Transform mode) to rotate & resize simultaneously
 * - Double-tap to reset to 0° and 100%
 */
class VlloTransformOverlayView(context: Context) : View(context) {

    var onGestureStart: (() -> Unit)? = null
    var onTransformChanged: ((rotationDegrees: Float, scaleFactor: Float) -> Unit)? = null
    var onDoubleTapReset: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var currentRotation = 0f
    private var currentScale = 1.0f
    private var showGuide = false

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 0.65f * density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC009CE9")
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    private var isInteracting = false
    private var startRotation = 0f
    private var startScale = 1.0f
    private var startAngleDeg = 0f
    private var startDistPx = 1f
    private var lastTapTimeMs = 0L

    fun updateTransformState(rotationDegrees: Float, scaleFactor: Float, showGuideBox: Boolean) {
        currentRotation = rotationDegrees
        currentScale = scaleFactor
        showGuide = showGuideBox
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGuide && !isInteracting) return

        val cx = width / 2f
        val cy = height / 2f
        val baseHalfW = min(width, height) * 0.34f * currentScale.coerceIn(0.4f, 1.8f)
        val baseHalfH = baseHalfW * 1.25f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(currentRotation)

        val rect = RectF(-baseHalfW, -baseHalfH, baseHalfW, baseHalfH)
        for (division in 1..3) {
            val x = rect.left + rect.width() * division / 4f
            val y = rect.top + rect.height() * division / 4f
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
        canvas.drawRect(rect, framePaint)

        // Draw 4 corner handles for 1-finger drag rotate & resize
        val corners = arrayOf(
            -baseHalfW to -baseHalfH,
            baseHalfW to -baseHalfH,
            baseHalfW to baseHalfH,
            -baseHalfW to baseHalfH
        )
        val r = 6f * density
        for ((x, y) in corners) {
            canvas.drawCircle(x, y, r, handleFillPaint)
            canvas.drawCircle(x, y, r, handleStrokePaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (showGuide && now - lastTapTimeMs < 280L) {
                    onDoubleTapReset?.invoke()
                    lastTapTimeMs = 0L
                    return true
                }
                lastTapTimeMs = now

                if (!showGuide) {
                    // Only intercept if user puts a second finger down, otherwise allow normal touches
                    return true
                }

                val dx = event.x - cx
                val dy = event.y - cy
                val dist = hypot(dx, dy)
                if (dist > 24f * density) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    isInteracting = true
                    startRotation = currentRotation
                    startScale = currentScale
                    startDistPx = dist
                    startAngleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    onGestureStart?.invoke()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (!isInteracting) {
                        onGestureStart?.invoke()
                    }
                    isInteracting = true
                    startRotation = currentRotation
                    startScale = currentScale
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    startDistPx = hypot(dx, dy).coerceAtLeast(20f)
                    startAngleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isInteracting) return false
                if (event.pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val dist = hypot(dx, dy).coerceAtLeast(20f)
                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val deltaAngle = angle - startAngleDeg
                    val scaleRatio = dist / startDistPx

                    val rawRot = snapAngle(startRotation + deltaAngle)
                    val newScale = (startScale * scaleRatio).coerceIn(0.3f, 4.0f)
                    currentRotation = rawRot
                    currentScale = newScale
                    onTransformChanged?.invoke(currentRotation, currentScale)
                    invalidate()
                } else if (showGuide) {
                    val dx = event.x - cx
                    val dy = event.y - cy
                    val dist = hypot(dx, dy).coerceAtLeast(20f)
                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val deltaAngle = angle - startAngleDeg
                    val scaleRatio = dist / startDistPx

                    val rawRot = snapAngle(startRotation + deltaAngle)
                    val newScale = (startScale * scaleRatio).coerceIn(0.3f, 4.0f)
                    currentRotation = rawRot
                    currentScale = newScale
                    onTransformChanged?.invoke(currentRotation, currentScale)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isInteracting = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapAngle(angle: Float): Float {
        var norm = angle % 360f
        if (norm > 180f) norm -= 360f
        if (norm <= -180f) norm += 360f
        val snapTargets = floatArrayOf(-180f, -90f, 0f, 90f, 180f)
        for (t in snapTargets) {
            if (abs(norm - t) <= 2.5f) return if (t == -180f) 180f else t
        }
        return norm
    }
}

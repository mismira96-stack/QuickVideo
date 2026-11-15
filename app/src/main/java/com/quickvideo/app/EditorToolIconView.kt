package com.quickvideo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Custom crisp vector-drawn 22dp x 22dp toolbar icons (Blue Theme).
 */
class EditorToolIconView(
    context: Context,
    var iconType: IconType
) : View(context) {

    enum class IconType {
        SPEED, ROTATE, MIRROR, MUTE_OFF, MUTE_ON, DELETE
    }

    private val density = resources.displayMetrics.density
    private var highlighted: Boolean = false

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setHighlighted(active: Boolean) {
        highlighted = active
        val c = if (active) Color.parseColor("#38BDF8") else Color.WHITE
        strokePaint.color = c
        fillPaint.color = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val cx = w / 2f
        val u = w / 24f

        when (iconType) {
            IconType.SPEED -> {
                val arcRect = RectF(3.5f * u, 5f * u, 20.5f * u, 21f * u)
                canvas.drawArc(arcRect, 180f, 180f, false, strokePaint)
                canvas.drawLine(cx, 14f * u, 16.5f * u, 8.5f * u, strokePaint)
                canvas.drawCircle(cx, 14f * u, 2f * u, fillPaint)
            }

            IconType.ROTATE -> {
                val rect = RectF(5f * u, 5f * u, 19f * u, 19f * u)
                canvas.drawArc(rect, -60f, 270f, false, strokePaint)
                val arrowHead = Path().apply {
                    moveTo(14.5f * u, 3.5f * u)
                    lineTo(18.5f * u, 6.8f * u)
                    lineTo(14.0f * u, 8.8f * u)
                }
                canvas.drawPath(arrowHead, strokePaint)
            }

            IconType.MIRROR -> {
                canvas.drawLine(cx, 4f * u, cx, 20f * u, strokePaint)
                val leftTri = Path().apply {
                    moveTo(9f * u, 7f * u)
                    lineTo(4f * u, 12f * u)
                    lineTo(9f * u, 17f * u)
                    close()
                }
                val rightTri = Path().apply {
                    moveTo(15f * u, 7f * u)
                    lineTo(20f * u, 12f * u)
                    lineTo(15f * u, 17f * u)
                    close()
                }
                canvas.drawPath(leftTri, strokePaint)
                canvas.drawPath(rightTri, strokePaint)
            }

            IconType.MUTE_OFF, IconType.MUTE_ON -> {
                val cone = Path().apply {
                    moveTo(4f * u, 9.5f * u)
                    lineTo(8f * u, 9.5f * u)
                    lineTo(13f * u, 5.5f * u)
                    lineTo(13f * u, 18.5f * u)
                    lineTo(8f * u, 14.5f * u)
                    lineTo(4f * u, 14.5f * u)
                    close()
                }
                canvas.drawPath(cone, strokePaint)
                if (iconType == IconType.MUTE_OFF) {
                    val waveRect = RectF(12f * u, 8f * u, 18.5f * u, 16f * u)
                    canvas.drawArc(waveRect, -45f, 90f, false, strokePaint)
                } else {
                    canvas.drawLine(16f * u, 9f * u, 21f * u, 15f * u, strokePaint)
                    canvas.drawLine(21f * u, 9f * u, 16f * u, 15f * u, strokePaint)
                }
            }

            IconType.DELETE -> {
                canvas.drawLine(5f * u, 7f * u, 19f * u, 7f * u, strokePaint)
                canvas.drawLine(9.5f * u, 4.5f * u, 14.5f * u, 4.5f * u, strokePaint)
                val bodyRect = RectF(7f * u, 7f * u, 17f * u, 19.5f * u)
                canvas.drawRoundRect(bodyRect, 2f * u, 2f * u, strokePaint)
                canvas.drawLine(10.5f * u, 10.5f * u, 10.5f * u, 16f * u, strokePaint)
                canvas.drawLine(13.5f * u, 10.5f * u, 13.5f * u, 16f * u, strokePaint)
            }
        }
    }
}

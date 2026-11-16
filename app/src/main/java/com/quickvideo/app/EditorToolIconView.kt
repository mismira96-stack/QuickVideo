package com.quickvideo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Line icons for the editor toolbars.
 */
class EditorToolIconView(
    context: Context,
    var iconType: IconType
) : View(context) {

    enum class IconType {
        SPEED, ROTATE, MIRROR, MUTE_OFF, MUTE_ON, DELETE,
        CUT_TO_START, CUT_BEFORE, SPLIT, CUT_AFTER, CUT_TO_END
    }

    private val density = resources.displayMetrics.density
    private val normalColor = Color.parseColor("#C9CED3")

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = normalColor
        style = Paint.Style.STROKE
        strokeWidth = 1.55f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = normalColor
        style = Paint.Style.FILL
    }

    fun setHighlighted(active: Boolean) {
        val c = if (active) Color.parseColor("#38BDF8") else normalColor
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
            IconType.CUT_TO_START, IconType.CUT_BEFORE, IconType.CUT_AFTER, IconType.CUT_TO_END -> {
                val reverse = iconType == IconType.CUT_AFTER || iconType == IconType.CUT_TO_END
                val toEdge = iconType == IconType.CUT_TO_START || iconType == IconType.CUT_TO_END
                canvas.save()
                if (reverse) canvas.scale(-1f, 1f, cx, 0f)
                val bracket = Path().apply {
                    if (toEdge) {
                        moveTo(10f * u, 4f * u)
                        lineTo(7f * u, 4f * u)
                        lineTo(7f * u, 20f * u)
                        lineTo(10f * u, 20f * u)
                    } else {
                        moveTo(19f * u, 4f * u)
                        lineTo(16f * u, 4f * u)
                        lineTo(16f * u, 20f * u)
                        lineTo(19f * u, 20f * u)
                    }
                }
                canvas.drawPath(bracket, strokePaint)
                val tip = if (toEdge) 12f else 12.5f
                val tail = if (toEdge) 20f else 4.5f
                val wing = if (toEdge) 15f else 9.5f
                canvas.drawLine(tail * u, 12f * u, tip * u, 12f * u, strokePaint)
                canvas.drawLine(tip * u, 12f * u, wing * u, 9f * u, strokePaint)
                canvas.drawLine(tip * u, 12f * u, wing * u, 15f * u, strokePaint)
                if (toEdge) canvas.drawLine(4f * u, 4f * u, 4f * u, 20f * u, strokePaint)
                canvas.restore()
            }

            IconType.SPLIT -> {
                canvas.drawCircle(7f * u, 6f * u, 2.7f * u, strokePaint)
                canvas.drawCircle(17f * u, 6f * u, 2.7f * u, strokePaint)
                canvas.drawLine(8.5f * u, 8f * u, 20f * u, 21f * u, strokePaint)
                canvas.drawLine(15.5f * u, 8f * u, 4f * u, 21f * u, strokePaint)
            }

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
